package dev.jamjet.runtime.protocols.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.executor.ExecutionResult;
import dev.jamjet.runtime.core.executor.NodeExecutionException;
import dev.jamjet.runtime.core.state.WorkItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolExecutorTest {

    private static WorkItem workItemWithPayload(JsonNode payload) {
        return new WorkItem(
                UUID.randomUUID(),
                ExecutionId.create(),
                "mcp-node-1",
                "mcp_tool",
                payload,
                1, 3,
                Instant.now(),
                null,
                null
        );
    }

    private static ObjectNode toolPayload(String server, String tool, JsonNode arguments) {
        ObjectNode payload = JamjetJson.shared().createObjectNode();
        payload.put("server", server);
        payload.put("tool", tool);
        if (arguments != null) {
            payload.set("arguments", arguments);
        }
        return payload;
    }

    @Test
    void execute_with_stub_caller_returns_result() throws NodeExecutionException {
        // Stub caller returns a fixed JSON node
        ObjectNode expectedOutput = JamjetJson.shared().createObjectNode();
        expectedOutput.put("answer", 42);

        Function<String, McpToolExecutor.ToolCaller> lookup = server -> {
            if ("test-server".equals(server)) {
                return (toolName, args) -> expectedOutput;
            }
            return null;
        };
        McpToolExecutor executor = new McpToolExecutor(lookup);

        ObjectNode args = JamjetJson.shared().createObjectNode();
        args.put("query", "hello");
        WorkItem item = workItemWithPayload(toolPayload("test-server", "search_tool", args));

        ExecutionResult result = executor.execute(item);

        assertThat(result).isNotNull();
        assertThat(result.output()).isEqualTo(expectedOutput);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_throws_when_server_not_found() {
        // Lookup always returns null
        McpToolExecutor executor = new McpToolExecutor(server -> null);

        WorkItem item = workItemWithPayload(toolPayload("missing-server", "any_tool", null));

        assertThatThrownBy(() -> executor.execute(item))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("missing-server")
                .satisfies(ex -> assertThat(((NodeExecutionException) ex).isRetryable()).isFalse());
    }

    @Test
    void execute_throws_when_tool_call_fails() {
        // Caller throws McpToolCallException
        McpToolExecutor executor = new McpToolExecutor(server ->
                (tool, args) -> { throw new McpToolCallException("tool blew up"); }
        );

        WorkItem item = workItemWithPayload(toolPayload("my-server", "broken_tool", null));

        assertThatThrownBy(() -> executor.execute(item))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("tool blew up")
                .satisfies(ex -> assertThat(((NodeExecutionException) ex).isRetryable()).isTrue());
    }
}
