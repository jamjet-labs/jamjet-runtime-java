package dev.jamjet.runtime.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetServerIntegrationTest {

    private final Javalin app = JamjetServer.createApp(
            new InMemoryStateBackend(), ServerConfig.defaults());

    @Test
    void fullRoundTrip() throws Exception {
        JavalinTest.test(app, (server, client) -> {
            // 1. Create a workflow
            var workflowBody = """
                    {"ir": {"workflow_id": "wf-integration", "version": "1", "nodes": []}}
                    """;
            var workflowResponse = client.post("/workflows", workflowBody);
            assertThat(workflowResponse.code()).isEqualTo(201);
            JsonNode workflowNode = JamjetJson.shared().readTree(workflowResponse.body().string());
            String workflowId = workflowNode.get("workflow_id").asText();
            assertThat(workflowId).isNotBlank();

            // 2. Start an execution
            var executionBody = String.format("""
                    {"workflow_id": "%s", "workflow_version": "1", "input": {}}
                    """, workflowId);
            var executionResponse = client.post("/executions", executionBody);
            assertThat(executionResponse.code()).isEqualTo(201);
            JsonNode executionNode = JamjetJson.shared().readTree(executionResponse.body().string());
            String executionId = executionNode.get("execution_id").asText();
            assertThat(executionId).isNotBlank();

            // 3. Enqueue a work item
            var enqueueBody = String.format("""
                    {
                      "execution_id": "%s",
                      "node_id": "node-1",
                      "queue_type": "default",
                      "payload": {"step": "process"}
                    }
                    """, executionId);
            var enqueueResponse = client.post("/work-items", enqueueBody);
            assertThat(enqueueResponse.code()).isEqualTo(201);

            // 4. Claim the work item
            var claimBody = """
                    {"worker_id": "worker-integration", "queue_types": ["default"]}
                    """;
            var claimResponse = client.post("/work-items/claim", claimBody);
            assertThat(claimResponse.code()).isEqualTo(200);
            JsonNode claimNode = JamjetJson.shared().readTree(claimResponse.body().string());
            String workItemId = claimNode.get("id").asText();
            assertThat(workItemId).isNotBlank();

            // 5. Complete the work item
            var completeResponse = client.post("/work-items/" + workItemId + "/complete", "{}");
            assertThat(completeResponse.code()).isEqualTo(204);

            // 6. Verify the execution is still retrievable
            var getExecutionResponse = client.get("/executions/" + executionId);
            assertThat(getExecutionResponse.code()).isEqualTo(200);
            var getExecutionBody = getExecutionResponse.body().string();
            assertThat(getExecutionBody).contains(executionId);

            // 7. Verify health endpoint
            var healthResponse = client.get("/health");
            assertThat(healthResponse.code()).isEqualTo(200);
        });
    }
}
