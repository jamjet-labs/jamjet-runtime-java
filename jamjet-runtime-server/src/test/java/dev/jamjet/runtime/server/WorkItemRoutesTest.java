package dev.jamjet.runtime.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkItemRoutesTest {

    private final Javalin app = JamjetServer.createApp(
            new InMemoryStateBackend(), ServerConfig.defaults());

    private String validExecutionId() {
        return ExecutionId.create().value().toString();
    }

    @Test
    void enqueueWorkItemReturns201() {
        JavalinTest.test(app, (server, client) -> {
            var execId = validExecutionId();
            var body = String.format("""
                    {
                      "execution_id": "%s",
                      "node_id": "node-1",
                      "queue_type": "default",
                      "payload": {"key": "value"}
                    }
                    """, execId);
            var response = client.post("/work-items", body);
            assertThat(response.code()).isEqualTo(201);
            var responseBody = response.body().string();
            assertThat(responseBody).contains("node-1");
        });
    }

    @Test
    void claimWorkItemReturns200WhenItemAvailable() throws Exception {
        JavalinTest.test(app, (server, client) -> {
            // Enqueue first
            var execId = validExecutionId();
            var enqueueBody = String.format("""
                    {
                      "execution_id": "%s",
                      "node_id": "node-1",
                      "queue_type": "default",
                      "payload": {}
                    }
                    """, execId);
            var enqueueResponse = client.post("/work-items", enqueueBody);
            assertThat(enqueueResponse.code()).isEqualTo(201);

            // Claim
            var claimBody = """
                    {"worker_id": "worker-1", "queue_types": ["default"]}
                    """;
            var claimResponse = client.post("/work-items/claim", claimBody);
            assertThat(claimResponse.code()).isEqualTo(200);
            var claimBody2 = claimResponse.body().string();
            assertThat(claimBody2).contains("worker-1");
        });
    }

    @Test
    void claimWorkItemReturns204WhenEmpty() {
        JavalinTest.test(app, (server, client) -> {
            var claimBody = """
                    {"worker_id": "worker-1", "queue_types": ["default"]}
                    """;
            var response = client.post("/work-items/claim", claimBody);
            assertThat(response.code()).isEqualTo(204);
        });
    }

    @Test
    void completeWorkItemReturns204() throws Exception {
        JavalinTest.test(app, (server, client) -> {
            // Enqueue
            var execId = validExecutionId();
            var enqueueBody = String.format("""
                    {
                      "execution_id": "%s",
                      "node_id": "node-1",
                      "queue_type": "default",
                      "payload": {}
                    }
                    """, execId);
            client.post("/work-items", enqueueBody);

            // Claim to get the item id
            var claimBody = """
                    {"worker_id": "worker-1", "queue_types": ["default"]}
                    """;
            var claimResponse = client.post("/work-items/claim", claimBody);
            JsonNode node = JamjetJson.shared().readTree(claimResponse.body().string());
            String itemId = node.get("id").asText();

            // Complete
            var completeResponse = client.post("/work-items/" + itemId + "/complete", "{}");
            assertThat(completeResponse.code()).isEqualTo(204);
        });
    }
}
