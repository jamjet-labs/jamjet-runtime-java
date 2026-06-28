package dev.jamjet.agent.tools;

import dev.jamjet.agent.TestTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the registry extracts OpenAI-format schemas from {@code @Tool} methods
 * exactly as the Python {@code @tool} decorator does (bare-type-string property
 * values, all params required), keys tools by class+method for B-3 dispatch, and
 * gates invocation to declared tools only.
 */
class ToolRegistryTest {

    @Test
    void extractsOpenAiSchemaForStringParamTool() {
        ToolRegistry registry = ToolRegistry.of(new TestTools.WebSearchTool());

        assertThat(registry.tools()).hasSize(1);
        RegisteredTool t = registry.tools().get(0);

        assertThat(t.name()).isEqualTo("web_search");
        assertThat(t.description()).isEqualTo("Search the web for a query.");
        assertThat(t.className()).isEqualTo(TestTools.WebSearchTool.class.getName());
        assertThat(t.methodName()).isEqualTo("webSearch");

        // The OpenAI function wrapper, mirroring agent_ir._tool_schema.
        assertThat(t.openAiSchema()).isEqualTo(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "web_search",
                        "description", "Search the web for a query.",
                        "parameters", Map.of(
                                "type", "object",
                                // Bare type string, NOT {"type":"string"} — matches Python.
                                "properties", Map.of("query", "string"),
                                "required", List.of("query")))));
    }

    @Test
    void mapsPrimitiveParamTypesToBareJsonSchemaStrings() {
        ToolRegistry registry = ToolRegistry.of(new TestTools.MathTool());
        RegisteredTool t = registry.tools().get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) t.inputSchema();
        assertThat(params).containsEntry("type", "object");
        assertThat(params.get("properties")).isEqualTo(Map.of("a", "integer", "b", "integer"));
        // Every Java @Tool parameter is required (no optional-param notion).
        assertThat(params.get("required")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void resolvesByClassAndMethodForWorkerDispatch() {
        TestTools.WebSearchTool holder = new TestTools.WebSearchTool();
        ToolRegistry registry = ToolRegistry.of(holder);

        RegisteredTool byKey = registry.byClassAndMethod(
                TestTools.WebSearchTool.class.getName(), "webSearch");
        assertThat(byKey).isNotNull();
        assertThat(byKey.name()).isEqualTo("web_search");
        // The reflective handle + instance the B-3 worker invokes.
        assertThat(byKey.method().getName()).isEqualTo("webSearch");
        assertThat(byKey.instance()).isSameAs(holder);

        // The model uses the tool NAME in its tool_call; that resolves too.
        assertThat(registry.byName("web_search")).isSameAs(byKey);
    }

    @Test
    void unregisteredClassMethodResolvesToNull() {
        ToolRegistry registry = ToolRegistry.of(new TestTools.WebSearchTool());
        // No registry entry -> the worker must fail, never Class.forName an
        // arbitrary class named in a payload.
        assertThat(registry.byClassAndMethod("com.evil.Rce", "exploit")).isNull();
        assertThat(registry.byName("nonexistent")).isNull();
    }

    @Test
    void preservesHolderOrderAndSortsMethodsWithinHolderByName() {
        // Holder order preserved across holders; @Tool methods within a holder
        // are name-sorted for determinism (getDeclaredMethods is unordered).
        ToolRegistry registry = ToolRegistry.of(
                new TestTools.WebSearchTool(), new TestTools.MultiTool());

        assertThat(registry.tools().stream().map(RegisteredTool::name).toList())
                .containsExactly("web_search", "alpha", "zebra");

        // Non-@Tool methods are not registered.
        assertThat(registry.byName("notATool")).isNull();
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThatThrownBy(() -> ToolRegistry.of(
                new TestTools.WebSearchTool(), new TestTools.WebSearchTool()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate @Tool name 'web_search'");
    }

    @Test
    void emptyRegistryHasNoSchemas() {
        ToolRegistry registry = new ToolRegistry();
        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.openAiToolSchemas()).isEmpty();
    }
}
