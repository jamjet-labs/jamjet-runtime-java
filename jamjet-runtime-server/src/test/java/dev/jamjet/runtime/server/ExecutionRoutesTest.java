package dev.jamjet.runtime.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionRoutesTest {

    private final Javalin app = JamjetServer.createApp(
            new InMemoryStateBackend(), ServerConfig.defaults());

    @Test
    void startExecutionReturns201() {
        JavalinTest.test(app, (server, client) -> {
            var body = """
                    {"workflow_id": "wf-test", "workflow_version": "1", "input": {}}
                    """;
            var response = client.post("/executions", body);
            assertThat(response.code()).isEqualTo(201);
            var responseBody = response.body().string();
            assertThat(responseBody).contains("execution_id");
            assertThat(responseBody).contains("pending");
        });
    }

    @Test
    void getExecutionById() throws Exception {
        JavalinTest.test(app, (server, client) -> {
            // Start an execution first
            var body = """
                    {"workflow_id": "wf-test", "workflow_version": "1", "input": {}}
                    """;
            var createResponse = client.post("/executions", body);
            assertThat(createResponse.code()).isEqualTo(201);

            // Parse the execution_id from response
            JsonNode node = JamjetJson.shared().readTree(createResponse.body().string());
            String executionId = node.get("execution_id").asText();

            var getResponse = client.get("/executions/" + executionId);
            assertThat(getResponse.code()).isEqualTo(200);
            var getBody = getResponse.body().string();
            assertThat(getBody).contains(executionId);
        });
    }

    @Test
    void getExecutionNotFoundReturns404() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/executions/00000000-0000-0000-0000-000000000000");
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void listExecutions() {
        JavalinTest.test(app, (server, client) -> {
            // Create an execution
            var body = """
                    {"workflow_id": "wf-test", "workflow_version": "1", "input": {}}
                    """;
            client.post("/executions", body);

            var response = client.get("/executions");
            assertThat(response.code()).isEqualTo(200);
            var responseBody = response.body().string();
            assertThat(responseBody).startsWith("[");
        });
    }

    @Test
    void listExecutionsWithStatusFilter() {
        JavalinTest.test(app, (server, client) -> {
            // Create an execution
            var body = """
                    {"workflow_id": "wf-test", "workflow_version": "1", "input": {}}
                    """;
            client.post("/executions", body);

            var response = client.get("/executions?status=pending");
            assertThat(response.code()).isEqualTo(200);
            var responseBody = response.body().string();
            assertThat(responseBody).contains("pending");
        });
    }

    @Test
    void cancelExecutionReturns200() throws Exception {
        JavalinTest.test(app, (server, client) -> {
            var body = """
                    {"workflow_id": "wf-test", "workflow_version": "1", "input": {}}
                    """;
            var createResponse = client.post("/executions", body);
            JsonNode node = JamjetJson.shared().readTree(createResponse.body().string());
            String executionId = node.get("execution_id").asText();

            var cancelResponse = client.post("/executions/" + executionId + "/cancel", "");
            assertThat(cancelResponse.code()).isEqualTo(200);
            var cancelBody = cancelResponse.body().string();
            assertThat(cancelBody).contains("cancelled");
        });
    }

    @Test
    void cancelNonexistentExecutionReturns404() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/executions/00000000-0000-0000-0000-000000000000/cancel", "");
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void getEventsForExecution() throws Exception {
        JavalinTest.test(app, (server, client) -> {
            var body = """
                    {"workflow_id": "wf-test", "workflow_version": "1", "input": {}}
                    """;
            var createResponse = client.post("/executions", body);
            JsonNode node = JamjetJson.shared().readTree(createResponse.body().string());
            String executionId = node.get("execution_id").asText();

            var eventsResponse = client.get("/executions/" + executionId + "/events");
            assertThat(eventsResponse.code()).isEqualTo(200);
            // Events list is initially empty
            assertThat(eventsResponse.body().string()).isEqualTo("[]");
        });
    }
}
