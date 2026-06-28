package dev.jamjet.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.JamjetJson;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java analog of Python's {@code jamjet.agents.tool_runtime.dispatch_tool_calls}:
 * runs one model turn's requested tool calls and returns the updated message list.
 *
 * <p>This is the method every {@code __tools_{t}__} {@code JavaFn} node points at
 * ({@code dev.jamjet.agent.tools.ToolDispatcher#dispatchToolCalls}). On the durable
 * engine the Phase B-3 {@code JavaToolWorker} resolves that fixed coordinate to a
 * {@code ToolDispatcher} bound to the agent's {@link ToolRegistry} and invokes
 * {@link #dispatchToolCalls(Map)} with the work item's {@code input} (the full
 * accumulated workflow state the scheduler enriched in — see the Rust scheduler's
 * {@code JavaFn} arm). The return replaces {@code state["messages"]} for the next
 * model turn, exactly as the Python dispatcher does.
 *
 * <h2>Input / output contract (mirrors {@code dispatch_tool_calls})</h2>
 * Reads from the state map, with the same fallbacks so it is unit-testable without
 * the engine:
 * <ul>
 *   <li>{@code messages} — the running message list (default {@code []}).</li>
 *   <li>{@code tool_calls} then {@code last_model_tool_calls} — the requested calls
 *       {@code [{id, name, arguments}, ...]} (the Model executor records the latter).</li>
 *   <li>{@code assistant_content} then {@code last_model_output} — the assistant text.</li>
 * </ul>
 * Returns {@code {"messages": <full updated list>}}: the original messages, plus the
 * assistant message that requested the calls (OpenAI shape), plus one {@code role:
 * tool} message per call. The worker uses this both as the node {@code output} and as
 * the {@code state_patch} (the engine merge-patches it into state, replacing the
 * top-level {@code messages} key) — identical to the Python {@code python_tool} worker.
 *
 * <h2>Tool resolution is registry-gated (the RCE gate)</h2>
 * Each call is resolved <strong>by name through {@link ToolRegistry#byName(String)}
 * only</strong>. The registry's name map is populated exclusively from
 * {@code @Tool}-annotated methods scanned off the agent's tool holders, so the only
 * reflective handles that exist are those pre-captured {@link RegisteredTool#method()}
 * objects. A model-supplied (hence untrusted) tool name that is not a declared
 * {@code @Tool} simply misses the map: there is <strong>no</strong> code path that
 * turns a name into a {@code Class.forName(...)} or an arbitrary {@code Method}, so a
 * hallucinated or malicious tool name can never invoke arbitrary code. An unknown tool
 * yields a clean {@code role: tool} error message surfaced to the model (so a single
 * hallucinated name does not abort the durable run), never an exception that crashes
 * the worker and never an arbitrary invocation. A <em>registered</em> tool that throws
 * at runtime propagates (the worker fails the item), mirroring the Python dispatcher.
 */
public final class ToolDispatcher {

    /** The fixed dispatch class coordinate the {@code JavaFn} tool nodes carry. */
    public static final String DISPATCH_CLASS = "dev.jamjet.agent.tools.ToolDispatcher";

    /** The fixed dispatch method coordinate the {@code JavaFn} tool nodes carry. */
    public static final String DISPATCH_METHOD = "dispatchToolCalls";

    private final ToolRegistry registry;
    private final ObjectMapper json;

    /** Bind a dispatcher to the agent's tool registry (the only callable tool set). */
    public ToolDispatcher(ToolRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
        this.json = JamjetJson.shared();
    }

    /**
     * Run the tool calls from one model turn and return the updated message list as
     * {@code {"messages": <full updated list>}}.
     *
     * @param input the accumulated workflow state (the work item's enriched {@code input})
     * @throws ToolInvocationException if a <em>registered</em> tool throws, or the
     *         dispatch thread is interrupted (lease lost) between calls
     */
    public Map<String, Object> dispatchToolCalls(Map<String, Object> input) {
        Map<String, Object> state = input == null ? Map.of() : input;

        List<Object> messages = new ArrayList<>(asList(state.get("messages")));
        List<Map<String, Object>> toolCalls = readToolCalls(state);
        String assistantContent = readAssistantContent(state);

        // 1. The assistant message that requested these calls (OpenAI shape, so the
        //    history replays cleanly into the next model turn).
        messages.add(assistantMessage(assistantContent, toolCalls));

        // 2. Execute each requested tool; append its result as a `role: tool` message.
        for (Map<String, Object> call : toolCalls) {
            // Cooperative abort checkpoint: if the worker cancelled us because the
            // lease was lost (M3), stop before running another (possibly
            // side-effecting) tool. The worker's lease-lost gate is the real
            // safety net; this just avoids extra work after an abort.
            if (Thread.currentThread().isInterrupted()) {
                throw new ToolInvocationException(
                        "<dispatch>", new InterruptedException("tool dispatch aborted (lease lost)"));
            }
            Object callId = call.get("id");
            String name = stringOrNull(call.get("name"));
            Map<String, Object> arguments = coerceArguments(call.get("arguments"));

            String content = invokeTool(name, arguments);
            messages.add(toolMessage(callId, name, content));
        }

        // 3. Return the FULL updated list — replaces state["messages"].
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", messages);
        return out;
    }

    // -- tool invocation (registry-gated) ---------------------------------------

    /**
     * Resolve and invoke one tool by name. A registry miss (unknown/unregistered
     * name) returns a clean error string for a {@code role: tool} message rather than
     * throwing — this is the safe, model-recoverable path and never touches an
     * arbitrary class. A registered tool that throws propagates as a
     * {@link ToolInvocationException} (the worker fails the item).
     */
    private String invokeTool(String name, Map<String, Object> arguments) {
        RegisteredTool tool = name == null ? null : registry.byName(name);
        if (tool == null) {
            // RCE GATE: an unknown name is not a declared @Tool. Do NOT reflect on it;
            // surface a clean tool error to the model.
            return "ERROR: tool '" + name + "' is not a registered @Tool";
        }

        Method method = tool.method();
        Object[] args = coerceToParameters(method, arguments);
        try {
            method.setAccessible(true);
            Object result = method.invoke(tool.instance(), args);
            return stringify(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ToolInvocationException(name, cause);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new ToolInvocationException(name, e);
        }
    }

    /**
     * Coerce the JSON argument map to the method's typed positional parameters via
     * Jackson, matching each parameter by its (compiled {@code -parameters}) name.
     * A missing argument coerces to {@code null} (or the primitive default).
     */
    private Object[] coerceToParameters(Method method, Map<String, Object> arguments) {
        Parameter[] params = method.getParameters();
        Object[] out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            Object raw = arguments.get(p.getName());
            out[i] = json.convertValue(raw, p.getType());
        }
        return out;
    }

    // -- message shaping (mirrors tool_runtime helpers) -------------------------

    private Map<String, Object> assistantMessage(String content, List<Map<String, Object>> toolCalls) {
        List<Object> calls = new ArrayList<>(toolCalls.size());
        for (Map<String, Object> call : toolCalls) {
            calls.add(assistantToolCall(call));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", content == null ? "" : content);
        m.put("tool_calls", calls);
        return m;
    }

    /** Render a {@code {id, name, arguments}} call into the OpenAI assistant shape. */
    private Map<String, Object> assistantToolCall(Map<String, Object> call) {
        Object rawArgs = call.get("arguments");
        String argString;
        if (rawArgs instanceof String s) {
            argString = s;
        } else {
            try {
                argString = json.writeValueAsString(rawArgs == null ? Map.of() : rawArgs);
            } catch (Exception e) {
                argString = "{}";
            }
        }
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.get("name"));
        function.put("arguments", argString);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", call.get("id"));
        m.put("type", "function");
        m.put("function", function);
        return m;
    }

    private Map<String, Object> toolMessage(Object callId, String name, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", callId);
        m.put("name", name);
        m.put("content", content);
        return m;
    }

    // -- state reading (with Python fallbacks) ----------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readToolCalls(Map<String, Object> state) {
        Object calls = state.get("tool_calls");
        if (calls == null) {
            calls = state.get("last_model_tool_calls");
        }
        if (calls instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    private String readAssistantContent(Map<String, Object> state) {
        Object content = state.get("assistant_content");
        if (content == null) {
            content = state.get("last_model_output");
        }
        return content == null ? null : String.valueOf(content);
    }

    /** Tool arguments arrive as a JSON object or a JSON string — normalise to a map. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceArguments(Object arguments) {
        if (arguments instanceof String s) {
            if (s.isBlank()) {
                return Map.of();
            }
            try {
                return json.readValue(s, Map.class);
            } catch (Exception e) {
                return Map.of();
            }
        }
        if (arguments instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /** Coerce a tool result to the string content a {@code role: tool} message needs. */
    private String stringify(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof String s) {
            return s;
        }
        try {
            return json.writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
