package dev.jamjet.agent.tools;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * One discovered {@link dev.jamjet.agent.Tool @Tool} method, the single source of
 * truth for both authoring (the OpenAI schema offered to the model) and durable
 * dispatch (the reflective handle the Phase B-3 worker resolves by class+method).
 *
 * @param name         the tool name offered to the model ({@code @Tool.name} or the method name)
 * @param description  the {@code @Tool.description} (may be empty)
 * @param className    the fully-qualified declaring class name (the JavaFn dispatch coordinate)
 * @param methodName   the method name (the JavaFn dispatch coordinate)
 * @param method       the reflective handle (used by the B-3 worker to invoke; never serialized)
 * @param instance     the tool-holder instance the method is invoked on (never serialized)
 * @param inputSchema  the JSON-schema {@code {type, properties, required}} for the parameters
 * @param openAiSchema the full OpenAI function wrapper {@code {type:function, function:{...}}}
 */
public record RegisteredTool(
        String name,
        String description,
        String className,
        String methodName,
        Method method,
        Object instance,
        Map<String, Object> inputSchema,
        Map<String, Object> openAiSchema
) {
    /** A stable registry key: {@code className#methodName} (the B-3 dispatch coordinate). */
    public String key() {
        return key(className, methodName);
    }

    /** Build the {@code className#methodName} registry key. */
    public static String key(String className, String methodName) {
        return className + "#" + methodName;
    }
}
