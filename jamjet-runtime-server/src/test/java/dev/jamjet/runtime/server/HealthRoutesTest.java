package dev.jamjet.runtime.server;

import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthRoutesTest {

    private final Javalin app = JamjetServer.createApp(
            new InMemoryStateBackend(), ServerConfig.defaults());

    @Test
    void healthEndpointReturns200WithStatusOk() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/health");
            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("\"status\"");
            assertThat(body).contains("ok");
        });
    }

    @Test
    void healthEndpointReturnsVersion() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/health");
            var body = response.body().string();
            assertThat(body).contains("\"version\"");
            assertThat(body).contains("0.1.0-SNAPSHOT");
        });
    }
}
