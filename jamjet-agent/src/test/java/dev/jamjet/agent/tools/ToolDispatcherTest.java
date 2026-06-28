package dev.jamjet.agent.tools;

import dev.jamjet.agent.TestTools;
import dev.jamjet.agent.Tool;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ToolDispatcher} &mdash; the registry-gated tool-call dispatcher
 * the {@code JavaFn} nodes point at. Mirrors the Python {@code dispatch_tool_calls}
 * INPUT/OUTPUT contract: reads {@code last_model_tool_calls} / {@code last_model_output}
 * (with the {@code tool_calls} / {@code assistant_content} fallbacks) from state, and
 * returns {@code {"messages": <full updated list>}} (assistant message + one tool message
 * per call). Includes the adversarial cases: arg coercion, unknown-tool clean error, and
 * a registered tool that throws.
 */
class ToolDispatcherTest {

    /** A tool that always throws, to prove a registered failure propagates. */
    public static final class BoomTool {
        @Tool(name = "boom", description = "always throws")
        public String boom(String why) {
            throw new IllegalStateException("kaboom: " + why);
        }
    }

    /** A tool that records whether it was invoked, to prove a null-coerced call never happens. */
    public static final class GreetTool {
        volatile boolean invoked = false;

        @Tool(name = "greet", description = "greets a person")
        public String greet(String name) {
            invoked = true;
            return "hello " + name;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messagesOf(Map<String, Object> out) {
        assertThat(out).containsKey("messages");
        return (List<Map<String, Object>>) out.get("messages");
    }

    private static Map<String, Object> toolCall(String id, String name, Map<String, Object> args) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.put("arguments", args);
        return c;
    }

    @Test
    void dispatchesRegisteredToolAndAppendsAssistantThenToolMessage() {
        var dispatcher = new ToolDispatcher(ToolRegistry.of(new TestTools.WebSearchTool()));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("last_model_output", "let me search");
        state.put("last_model_tool_calls",
                List.of(toolCall("tc1", "web_search", Map.of("query", "jamjet"))));

        List<Map<String, Object>> messages = messagesOf(dispatcher.dispatchToolCalls(state));

        // [0] the assistant message that requested the call (OpenAI shape).
        assertThat(messages).hasSize(2);
        Map<String, Object> assistant = messages.get(0);
        assertThat(assistant).containsEntry("role", "assistant").containsEntry("content", "let me search");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) assistant.get("tool_calls");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0)).containsEntry("id", "tc1").containsEntry("type", "function");
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) calls.get(0).get("function");
        assertThat(fn).containsEntry("name", "web_search");
        assertThat((String) fn.get("arguments")).contains("jamjet"); // arguments are a JSON string

        // [1] the tool result message.
        Map<String, Object> toolMsg = messages.get(1);
        assertThat(toolMsg)
                .containsEntry("role", "tool")
                .containsEntry("tool_call_id", "tc1")
                .containsEntry("name", "web_search")
                .containsEntry("content", "results for jamjet");
    }

    @Test
    void coercesJsonArgsToTypedMethodParameters() {
        // add_numbers(int a, int b) with JSON numbers -> "5".
        var dispatcher = new ToolDispatcher(ToolRegistry.of(new TestTools.MathTool()));

        Map<String, Object> state = Map.of(
                "last_model_tool_calls",
                List.of(toolCall("tc1", "add_numbers", Map.of("a", 2, "b", 3))));

        List<Map<String, Object>> messages = messagesOf(dispatcher.dispatchToolCalls(state));
        assertThat(messages.get(1)).containsEntry("content", "5");
    }

    @Test
    void argumentsSuppliedAsJsonStringAreParsed() {
        var dispatcher = new ToolDispatcher(ToolRegistry.of(new TestTools.WebSearchTool()));

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "tc1");
        call.put("name", "web_search");
        call.put("arguments", "{\"query\":\"from-string\"}"); // a JSON STRING, not an object

        Map<String, Object> state = Map.of("last_model_tool_calls", List.of(call));
        List<Map<String, Object>> messages = messagesOf(dispatcher.dispatchToolCalls(state));
        assertThat(messages.get(1)).containsEntry("content", "results for from-string");
    }

    @Test
    void unknownToolYieldsCleanErrorMessageNotExceptionAndNoInvocation() {
        // RCE gate at the name-resolution level: an unregistered name is never turned
        // into a class/method; it surfaces a clean tool error to the model.
        var dispatcher = new ToolDispatcher(ToolRegistry.of(new TestTools.WebSearchTool()));

        Map<String, Object> state = Map.of(
                "last_model_tool_calls",
                List.of(toolCall("tc1", "java.lang.Runtime", Map.of("cmd", "rm -rf /"))));

        Map<String, Object> out = dispatcher.dispatchToolCalls(state); // must NOT throw
        List<Map<String, Object>> messages = messagesOf(out);
        assertThat(messages.get(1))
                .containsEntry("role", "tool")
                .containsEntry("tool_call_id", "tc1");
        assertThat((String) messages.get(1).get("content"))
                .contains("not a registered @Tool")
                .contains("java.lang.Runtime");
    }

    @Test
    void missingRequiredArgYieldsCleanToolErrorNotANullCall() {
        // Every @Tool param is required (buildInputSchema marks them all). A call missing
        // a required arg must surface a clean role:tool error to the model, NOT invoke the
        // tool with a null-coerced argument.
        GreetTool greet = new GreetTool();
        var dispatcher = new ToolDispatcher(ToolRegistry.of(greet));

        Map<String, Object> state = Map.of(
                "last_model_tool_calls",
                List.of(toolCall("tc1", "greet", Map.of()))); // no "name" arg

        Map<String, Object> out = dispatcher.dispatchToolCalls(state); // must NOT throw
        List<Map<String, Object>> messages = messagesOf(out);
        assertThat(messages.get(1))
                .containsEntry("role", "tool")
                .containsEntry("tool_call_id", "tc1");
        assertThat((String) messages.get(1).get("content"))
                .contains("missing required argument")
                .contains("name");
        // CRITICAL: the tool was never invoked with a null arg.
        assertThat(greet.invoked).isFalse();
    }

    @Test
    void toolInvocationExceptionMessageDoesNotLeakCauseDetail() {
        // The surfaced message is logged + persisted to the engine via failWorkItem, so it
        // must stay generic (tool name only) — never embed the raw cause.toString().
        ToolInvocationException ex = new ToolInvocationException(
                "wire_money", new IllegalStateException("secret-account-9988"));
        assertThat(ex.getMessage())
                .contains("wire_money")
                .doesNotContain("secret-account-9988")
                .doesNotContain("IllegalStateException");
        // The cause is retained as the exception cause (just not in the message text).
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(ex.toolName()).isEqualTo("wire_money");
    }

    @Test
    void registeredToolThatThrowsPropagatesAsToolInvocationException() {
        var dispatcher = new ToolDispatcher(ToolRegistry.of(new BoomTool()));

        Map<String, Object> state = Map.of(
                "last_model_tool_calls",
                List.of(toolCall("tc1", "boom", Map.of("why", "test"))));

        assertThatThrownBy(() -> dispatcher.dispatchToolCalls(state))
                .isInstanceOf(ToolInvocationException.class)
                .hasMessageContaining("boom")
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void preservesExistingMessagesAndOrdersMultipleToolCalls() {
        var dispatcher = new ToolDispatcher(
                ToolRegistry.of(new TestTools.WebSearchTool(), new TestTools.MathTool()));

        Map<String, Object> prior = Map.of("role", "user", "content", "hi");
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("messages", List.of(prior));
        state.put("last_model_tool_calls", List.of(
                toolCall("a", "web_search", Map.of("query", "q")),
                toolCall("b", "add_numbers", Map.of("a", 10, "b", 1))));

        List<Map<String, Object>> messages = messagesOf(dispatcher.dispatchToolCalls(state));
        // prior user msg + 1 assistant + 2 tool msgs, in order.
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).containsEntry("role", "user");
        assertThat(messages.get(1)).containsEntry("role", "assistant");
        assertThat(messages.get(2)).containsEntry("tool_call_id", "a").containsEntry("content", "results for q");
        assertThat(messages.get(3)).containsEntry("tool_call_id", "b").containsEntry("content", "11");
    }

    @Test
    void prefersExplicitToolCallsAndAssistantContentFallbackKeys() {
        var dispatcher = new ToolDispatcher(ToolRegistry.of(new TestTools.WebSearchTool()));

        // Both keys present: tool_calls / assistant_content take precedence.
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("assistant_content", "preferred");
        state.put("last_model_output", "ignored");
        state.put("tool_calls", List.of(toolCall("tc1", "web_search", Map.of("query", "x"))));
        state.put("last_model_tool_calls", List.of()); // would be empty if used

        List<Map<String, Object>> messages = messagesOf(dispatcher.dispatchToolCalls(state));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("content", "preferred");
        assertThat(messages.get(1)).containsEntry("content", "results for x");
    }

    @Test
    void emptyInputYieldsOnlyTheAssistantMessage() {
        var dispatcher = new ToolDispatcher(ToolRegistry.of(new TestTools.WebSearchTool()));
        List<Map<String, Object>> messages = messagesOf(dispatcher.dispatchToolCalls(Map.of()));
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).containsEntry("role", "assistant").containsEntry("content", "");
    }
}
