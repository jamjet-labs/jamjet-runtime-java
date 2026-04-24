package dev.jamjet.runtime.protocols.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerAdapterTest {

    private InMemoryStateBackend stateBackend;
    private McpServerAdapter adapter;

    @BeforeEach
    void setUp() {
        stateBackend = new InMemoryStateBackend();
        adapter = new McpServerAdapter(stateBackend);
    }

    @Test
    void protocol_returns_mcp() {
        assertThat(adapter.protocol()).isEqualTo("mcp");
    }

    @Test
    void listToolNames_contains_expected_tools() {
        assertThat(adapter.listToolNames()).containsExactlyInAnyOrder(
                "jamjet_run_workflow",
                "jamjet_get_execution",
                "jamjet_list_executions",
                "jamjet_cancel_execution",
                "jamjet_get_events",
                "jamjet_approve"
        );
    }

    @Test
    void handleRunWorkflow_creates_execution() {
        ObjectNode args = JamjetJson.shared().createObjectNode();
        args.put("workflow_id", "my-workflow");
        args.put("version", "1.0");

        JsonNode result = adapter.handleToolCall("jamjet_run_workflow", args);

        assertThat(result.has("error")).isFalse();
        assertThat(result.get("workflow_id").asText()).isEqualTo("my-workflow");
        assertThat(result.get("version").asText()).isEqualTo("1.0");
        assertThat(result.get("status").asText()).isEqualTo("pending");
        String executionId = result.get("execution_id").asText();
        assertThat(executionId).isNotBlank();
    }

    @Test
    void handleGetExecution_retrieves_execution() {
        // First create an execution
        ObjectNode createArgs = JamjetJson.shared().createObjectNode();
        createArgs.put("workflow_id", "wf-123");
        createArgs.put("version", "2.0");
        JsonNode created = adapter.handleToolCall("jamjet_run_workflow", createArgs);
        String execId = created.get("execution_id").asText();

        // Now retrieve it
        ObjectNode getArgs = JamjetJson.shared().createObjectNode();
        getArgs.put("execution_id", execId);
        JsonNode retrieved = adapter.handleToolCall("jamjet_get_execution", getArgs);

        assertThat(retrieved.has("error")).isFalse();
        assertThat(retrieved.get("execution_id").asText()).isEqualTo(execId);
        assertThat(retrieved.get("workflow_id").asText()).isEqualTo("wf-123");
        assertThat(retrieved.get("status").asText()).isEqualTo("pending");
    }

    @Test
    void handleToolCall_unknown_tool_returns_error_node() {
        JsonNode result = adapter.handleToolCall("no_such_tool", null);

        assertThat(result.get("error").asText()).isEqualTo("unknown_tool");
        assertThat(result.get("message").asText()).contains("no_such_tool");
    }
}
