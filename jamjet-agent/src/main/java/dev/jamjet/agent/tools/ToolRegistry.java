package dev.jamjet.agent.tools;

import dev.jamjet.agent.Tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers {@link Tool @Tool} methods on tool-holder objects and is the single
 * source of truth for both halves of the Java agent loop:
 *
 * <ul>
 *   <li><b>Authoring</b> — {@link #openAiToolSchemas()} returns the OpenAI-format
 *       function schemas (in deterministic order) that the {@code Agent} builder
 *       threads into every Model node's {@code tools}, mirroring the Python
 *       {@code agent_ir._tool_schema}.</li>
 *   <li><b>Dispatch</b> — {@link #byClassAndMethod} lets the Phase B-3 durable
 *       worker resolve a {@code JavaFn} node's {@code class_name}+{@code method}
 *       back to the exact {@link RegisteredTool} (and its reflective handle),
 *       gating invocation to declared {@code @Tool} methods only (no arbitrary
 *       {@code Class.forName} from a payload field).</li>
 * </ul>
 *
 * <p>Tool order is deterministic: holders are kept in registration order and,
 * within a holder, {@code @Tool} methods are ordered by name (since
 * {@link Class#getDeclaredMethods()} is unordered). This keeps the emitted IR
 * stable across runs and JVMs, which the content-version cache key relies on.
 *
 * <p>The OpenAI schema mirrors the Python {@code @tool} decorator exactly,
 * including its bare-type-string property values ({@code {"query": "string"}},
 * not {@code {"query": {"type": "string"}}}) so the schemas a Java agent offers
 * the model are byte-identical to the Python equivalent.
 */
public final class ToolRegistry {

    private final List<RegisteredTool> tools = new ArrayList<>();
    private final Map<String, RegisteredTool> byKey = new LinkedHashMap<>();
    private final Map<String, RegisteredTool> byName = new LinkedHashMap<>();

    /** An empty registry. */
    public ToolRegistry() {}

    /**
     * Build a registry from the given tool-holder instances, registered in order.
     * Each holder is scanned for {@code @Tool} methods.
     */
    public static ToolRegistry of(Object... holders) {
        ToolRegistry registry = new ToolRegistry();
        for (Object holder : holders) {
            registry.register(holder);
        }
        return registry;
    }

    /**
     * Build a registry from the given tool-holder instances, registered in order.
     */
    public static ToolRegistry of(List<?> holders) {
        ToolRegistry registry = new ToolRegistry();
        for (Object holder : holders) {
            registry.register(holder);
        }
        return registry;
    }

    /**
     * Scan {@code holder}'s class for {@code @Tool} methods and add them, in
     * method-name order. Returns {@code this} for chaining.
     *
     * @throws IllegalArgumentException if two registered tools share a name
     */
    public ToolRegistry register(Object holder) {
        if (holder == null) {
            throw new IllegalArgumentException("tool holder must not be null");
        }
        Class<?> clazz = holder.getClass();

        List<Method> annotated = new ArrayList<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                annotated.add(m);
            }
        }
        // getDeclaredMethods() has no defined order — sort by name for determinism.
        annotated.sort(Comparator.comparing(Method::getName));

        for (Method m : annotated) {
            Tool ann = m.getAnnotation(Tool.class);
            String name = ann.name().isBlank() ? m.getName() : ann.name();
            String description = ann.description();

            Map<String, Object> inputSchema = buildInputSchema(m);
            Map<String, Object> openAi = openAiSchema(name, description, inputSchema);

            RegisteredTool rt = new RegisteredTool(
                    name, description, clazz.getName(), m.getName(), m, holder, inputSchema, openAi);

            if (byName.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Duplicate @Tool name '" + name + "' (from " + clazz.getName() + "#" + m.getName()
                                + "); tool names must be unique within an agent.");
            }
            tools.add(rt);
            byKey.put(rt.key(), rt);
            byName.put(name, rt);
        }
        return this;
    }

    /** The registered tools in deterministic order. */
    public List<RegisteredTool> tools() {
        return Collections.unmodifiableList(tools);
    }

    /** {@code true} if no tools are registered. */
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * The OpenAI-format function schemas for every registered tool, in order —
     * the value the {@code Agent} builder puts in each Model node's {@code tools}.
     */
    public List<Map<String, Object>> openAiToolSchemas() {
        List<Map<String, Object>> out = new ArrayList<>(tools.size());
        for (RegisteredTool t : tools) {
            out.add(t.openAiSchema());
        }
        return out;
    }

    /**
     * Resolve a tool by its dispatch coordinate ({@code className}+{@code method}).
     * This is the B-3 worker's lookup: a registry hit means the method is a
     * declared {@code @Tool}, so reflective invocation is safe.
     */
    public RegisteredTool byClassAndMethod(String className, String methodName) {
        return byKey.get(RegisteredTool.key(className, methodName));
    }

    /** Resolve a tool by the name the model used in its {@code tool_call}. */
    public RegisteredTool byName(String name) {
        return byName.get(name);
    }

    // -- schema construction (mirrors Python jamjet.tools.decorators) -----------

    private static Map<String, Object> buildInputSchema(Method m) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            String paramName = p.getName();
            properties.put(paramName, jsonType(p.getType()));
            // Java reflection exposes no notion of an optional/defaulted parameter,
            // so every @Tool parameter is required (parity with the Python schema
            // where a param without a default is required).
            required.add(paramName);
        }
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> openAiSchema(String name, String description, Map<String, Object> inputSchema) {
        LinkedHashMap<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", inputSchema);

        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    /**
     * Map a Java parameter type to the bare JSON-schema type string the Python
     * {@code _type_to_schema} emits ({@code "string"}/{@code "integer"}/
     * {@code "number"}/{@code "boolean"}/{@code "object"}). Integer-family and
     * floating-family Java types widen to {@code "integer"}/{@code "number"}.
     */
    private static String jsonType(Class<?> t) {
        if (t == String.class || t == CharSequence.class || t == char.class || t == Character.class) {
            return "string";
        }
        if (t == int.class || t == Integer.class
                || t == long.class || t == Long.class
                || t == short.class || t == Short.class
                || t == byte.class || t == Byte.class) {
            return "integer";
        }
        if (t == float.class || t == Float.class || t == double.class || t == Double.class) {
            return "number";
        }
        if (t == boolean.class || t == Boolean.class) {
            return "boolean";
        }
        // Anything else (records, POJOs, collections, ...) is an object.
        return "object";
    }
}
