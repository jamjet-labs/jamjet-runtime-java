package dev.jamjet.runtime.server;

import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRoutesTest {

    private final Javalin app = JamjetServer.createApp(
            new InMemoryStateBackend(), ServerConfig.defaults());

    @Test
    void createWorkflowReturns201() {
        JavalinTest.test(app, (server, client) -> {
            var body = """
                    {"ir": {"workflow_id": "wf-test", "version": "1", "nodes": []}}
                    """;
            var response = client.post("/workflows", body);
            assertThat(response.code()).isEqualTo(201);
            var responseBody = response.body().string();
            assertThat(responseBody).contains("wf-test");
        });
    }

    @Test
    void createWorkflowWithoutIrReturns400() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/workflows", "{}");
            assertThat(response.code()).isEqualTo(400);
        });
    }

    @Test
    void createWorkflowGeneratesWorkflowIdIfMissing() {
        JavalinTest.test(app, (server, client) -> {
            var body = """
                    {"ir": {"nodes": []}}
                    """;
            var response = client.post("/workflows", body);
            assertThat(response.code()).isEqualTo(201);
            var responseBody = response.body().string();
            assertThat(responseBody).contains("workflow_id");
        });
    }
}
