package dev.jamjet.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.agent.client.ExecutionState;
import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.client.StartExecutionResult;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.ir.WorkflowIr;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Drives a compiled {@link Agent} through one durable run on the JamJet engine —
 * the mechanics behind {@link Agent#runDurable(String)}, mirroring the Python
 * {@code Agent.run_durable}: compile &rarr; {@code create_workflow} &rarr;
 * {@code start_execution} (seeded with the system+user {@code messages} and the
 * tool-resolver map) &rarr; poll {@code get_execution} to a terminal state &rarr;
 * extract the final assistant text + tool-call trace into an {@link AgentResult}.
 *
 * <p>Unlike the in-process path, every model call and tool dispatch runs through the
 * durable event-sourced engine, so the run gets the event log, replay, idempotency,
 * and fail-closed governance enforcement (budget / policy / PII) compiled into the IR.
 *
 * <h2>Where the terminal result lives</h2>
 * The Rust Model executor writes the final model turn's content <em>inline</em> into
 * {@code current_state.last_model_output} (via its {@code state_patch}; never spilled
 * to the artifact store), so the canonical answer is {@code last_model_output}. The
 * fallback is the last assistant message's content in the accumulated
 * {@code current_state.messages} (which the Java tool dispatcher replaces each turn).
 * The terminal is always reached via a model turn (the final tool-less
 * {@code __model_{maxTurns}__} node), so the answer is a real model turn that saw the
 * tool results — exactly as in the Python contract.
 */
final class DurableRunner {

    /**
     * Terminal {@code WorkflowStatus} strings, snake_case as the Rust engine
     * serializes them ({@code runtime/core/src/workflow.rs}: {@code rename_all =
     * "snake_case"}), matching the Python {@code _TERMINAL_STATUSES}.
     */
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("completed", "failed", "cancelled", "limit_exceeded");

    /** The single status the run treats as success; every other terminal is an error. */
    private static final String COMPLETED = "completed";

    private static final ObjectMapper JSON = JamjetJson.shared();

    private DurableRunner() {}

    /**
     * Run {@code agent} on {@code prompt} over {@code client}, returning the extracted
     * {@link AgentResult}. The caller owns {@code client}'s lifecycle (this method does
     * NOT close it), so a worker and a run can share one client.
     *
     * @throws AgentRunException         if the run reaches a non-{@code completed} terminal state
     * @throws AgentRunTimeoutException  if no terminal state is reached before the deadline
     */
    static AgentResult run(Agent agent, String prompt, JamjetEngineClient client, RunOptions options) {
        WorkflowIr ir = agent.compileToIr(options.maxTurns());
        Map<String, Object> initialInput = AgentIrCompiler.buildInitialState(agent, prompt);

        // Register the compiled IR, then start an execution seeded with the running
        // messages + tool-resolver map (build_initial_state). The version is the
        // content hash so a changed agent never reuses an immutably-cached graph.
        client.createWorkflow(ir);
        StartExecutionResult started = client.startExecution(ir.workflowId(), initialInput, ir.version());
        String execId = started == null ? null : started.executionId();
        if (execId == null || execId.isBlank()) {
            throw new AgentRunException("unknown",
                    "start_execution returned no execution_id (started=" + started + ")");
        }

        Duration timeout = options.timeout() != null
                ? options.timeout()
                : Duration.ofSeconds(agent.timeoutSeconds());
        ExecutionState terminal = pollToTerminal(client, execId, options.pollInterval(), timeout);
        return extractResult(terminal);
    }

    /** Poll {@code get_execution} until a terminal status, mirroring Python {@code _poll_to_terminal}. */
    private static ExecutionState pollToTerminal(JamjetEngineClient client, String execId,
                                                 Duration pollInterval, Duration timeout) {
        // Monotonic deadline: time spent inside slow get_execution() calls counts
        // toward the timeout, so a stalled run can't blow past it.
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        String lastStatus = "unknown";
        while (true) {
            ExecutionState execution = client.getExecution(execId);
            lastStatus = execution == null ? "unknown" : execution.status();
            if (isTerminal(lastStatus)) {
                return execution;
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new AgentRunTimeoutException(lastStatus,
                        "durable agent run " + execId + " did not reach a terminal state within "
                                + timeout.toSeconds() + "s (last status: " + lastStatus + ")");
            }
            if (sleep(pollInterval.toMillis())) {
                throw new AgentRunTimeoutException(lastStatus,
                        "durable agent run " + execId + " interrupted while polling (last status: " + lastStatus + ")");
            }
        }
    }

    /**
     * Build an {@link AgentResult} from a terminal execution snapshot, mirroring the
     * Python {@code _extract_result}: the answer is {@code current_state.last_model_output}
     * (the inline final model turn), falling back to the last assistant message; the
     * tool-call trace is reconstructed from {@code current_state.messages}.
     *
     * @throws AgentRunException if the terminal status is not {@code completed}
     */
    static AgentResult extractResult(ExecutionState execution) {
        String status = normalize(execution == null ? null : execution.status());
        if (!COMPLETED.equals(status)) {
            String detail = failureDetail(execution, status);
            throw new AgentRunException(status,
                    "durable agent run ended in non-completed state: " + detail);
        }

        Map<String, Object> state = execution.currentState() == null ? Map.of() : execution.currentState();
        List<Object> messages = asList(state.get("messages"));

        Object output = state.get("last_model_output");
        if (output == null) {
            output = lastAssistantContent(messages);
        }
        String outputStr = output == null ? "" : String.valueOf(output);

        return new AgentResult(outputStr, toolCallsFromMessages(messages), execution);
    }

    // -- result reconstruction (mirrors agent.py helpers) -----------------------

    /** The content of the last assistant message that carried text. */
    private static String lastAssistantContent(List<Object> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = asMap(messages.get(i));
            if ("assistant".equals(msg.get("role"))) {
                Object content = msg.get("content");
                if (content != null && !String.valueOf(content).isEmpty()) {
                    return String.valueOf(content);
                }
            }
        }
        return null;
    }

    /**
     * Reconstruct the tool-call trace from the message history, mirroring the Python
     * {@code _tool_calls_from_messages}: pair each {@code role: tool} result with the
     * arguments of the assistant {@code tool_call} that requested it (matched by
     * {@code tool_call_id}).
     */
    private static List<AgentResult.ToolCall> toolCallsFromMessages(List<Object> messages) {
        Map<Object, Map<String, Object>> argsById = new LinkedHashMap<>();
        for (Object raw : messages) {
            Map<String, Object> msg = asMap(raw);
            if (!"assistant".equals(msg.get("role"))) {
                continue;
            }
            for (Object callRaw : asList(msg.get("tool_calls"))) {
                Map<String, Object> call = asMap(callRaw);
                Map<String, Object> function = asMap(call.get("function"));
                Map<String, Object> parsed = parseArguments(function.get("arguments"));
                Object id = call.get("id");
                if (id != null) {
                    argsById.put(id, parsed);
                }
            }
        }

        List<AgentResult.ToolCall> calls = new ArrayList<>();
        for (Object raw : messages) {
            Map<String, Object> msg = asMap(raw);
            if (!"tool".equals(msg.get("role"))) {
                continue;
            }
            Object name = msg.get("name");
            Map<String, Object> input = argsById.getOrDefault(msg.get("tool_call_id"), Map.of());
            Object content = msg.get("content");
            calls.add(new AgentResult.ToolCall(
                    name == null ? null : String.valueOf(name),
                    input,
                    content == null ? null : String.valueOf(content)));
        }
        return calls;
    }

    /** Tool-call arguments arrive as a JSON object or a JSON string — normalise to a map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArguments(Object arguments) {
        if (arguments instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (arguments instanceof String s && !s.isBlank()) {
            try {
                return JSON.readValue(s, Map.class);
            } catch (Exception e) {
                return Map.of();
            }
        }
        return Map.of();
    }

    // -- status / detail --------------------------------------------------------

    private static boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(normalize(status));
    }

    /** Lower-case the status so a snake_case engine ({@code completed}) and any capitalized stub agree. */
    private static String normalize(String status) {
        return status == null ? "" : status.toLowerCase(Locale.ROOT);
    }

    /** Best-effort failure detail for a non-completed terminal: a {@code detail}/{@code error} in state, else the status. */
    private static String failureDetail(ExecutionState execution, String status) {
        if (execution != null && execution.currentState() != null) {
            Object detail = execution.currentState().get("detail");
            if (detail == null) {
                detail = execution.currentState().get("error");
            }
            if (detail != null && !String.valueOf(detail).isBlank()) {
                return status + " (" + detail + ")";
            }
        }
        return status;
    }

    // -- coercion helpers -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /** Sleep, returning true if interrupted (so the poll loop can exit cleanly). */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
