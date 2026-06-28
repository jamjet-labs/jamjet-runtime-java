package dev.jamjet.agent;

import dev.jamjet.agent.client.ExecutionState;

import java.util.List;
import java.util.Map;

/**
 * The result of a durable agent run ({@link Agent#runDurable(String)}) — the Java
 * analog of the Python {@code jamjet.agents.agent.AgentResult}. It carries the SAME
 * load-bearing fields the Python durable path extracts:
 *
 * <ul>
 *   <li>{@link #output()} — the final assistant text. On the durable engine this is
 *       the terminal {@code current_state.last_model_output} (written inline by the
 *       Rust Model executor's {@code state_patch}); it falls back to the last
 *       assistant message's content in {@code current_state.messages}.</li>
 *   <li>{@link #toolCalls()} — the tool-call trace, reconstructed from the
 *       accumulated {@code messages} (each {@code role: tool} result paired with the
 *       arguments of the assistant {@code tool_call} that requested it, matched by
 *       {@code tool_call_id}), mirroring the Python {@code _tool_calls_from_messages}.</li>
 *   <li>{@link #terminalState()} — the raw terminal execution snapshot
 *       ({@code GET /executions/{id}}), so callers can inspect the full
 *       {@code current_state} / status without re-fetching.</li>
 * </ul>
 *
 * <p>Governance artifacts (budget enforcement, policy gates, PII redaction, the audit
 * log) ride in the durable engine itself — they are compiled into the {@link Agent}'s
 * {@code WorkflowIr} (see {@link Agent#compileToIr()}) and enforced fail-closed by the
 * Rust engine, not re-derived here. The terminal state reflects their outcome (e.g. a
 * {@code limit_exceeded} status surfaces as an {@link AgentRunException}).
 */
public record AgentResult(
        String output,
        List<ToolCall> toolCalls,
        ExecutionState terminalState
) {
    public AgentResult {
        output = output == null ? "" : output;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * One reconstructed tool call from the durable run's message history — the Java
     * analog of the Python {@code ToolCallRecord} dict ({@code tool} / {@code input} /
     * {@code output}). Per-call durations are not carried in the message history, so
     * (unlike the in-process path) no duration is reconstructed here.
     *
     * @param tool   the tool name the model called
     * @param input  the parsed arguments the model passed (possibly empty)
     * @param output the tool's returned content (the {@code role: tool} message body)
     */
    public record ToolCall(String tool, Map<String, Object> input, String output) {
        public ToolCall {
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }

    @Override
    public String toString() {
        return "AgentResult(output=" + output + ", toolCalls=" + toolCalls.size() + ")";
    }
}
