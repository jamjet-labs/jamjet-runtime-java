package dev.jamjet.runtime.server;

import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthHookTest {

    private static final String WORKFLOW = """
            {"ir": {"workflow_id": "wf", "version": "1", "nodes": []}}""";

    private final InMemoryStateBackend backend = new InMemoryStateBackend();

    private Javalin appWithToken(String token) {
        return JamjetServer.createApp(backend, new ServerConfig(0, "in-memory", null, null, token));
    }

    @Test
    void withATokenEveryRouteButHealthNeedsIt() {
        JavalinTest.test(appWithToken("s3cret"), (server, client) -> {
            assertThat(client.get("/health").code()).isEqualTo(200);

            assertThat(client.get("/executions").code()).isEqualTo(401);
            assertThat(client.get("/executions", r -> r.header("Authorization", "Bearer wrong")).code())
                    .isEqualTo(401);
            assertThat(client.get("/executions", r -> r.header("Authorization", "Basic s3cret")).code())
                    .isEqualTo(401);
            assertThat(client.get("/executions", r -> r.header("Authorization", "Bearer s3cret")).code())
                    .isEqualTo(200);
            assertThat(client.get("/executions", r -> r.header("Authorization", "bearer s3cret")).code())
                    .isEqualTo(200);
        });
    }

    @Test
    void aRefusedRequestHasNoEffect() throws Exception {
        JavalinTest.test(appWithToken("s3cret"), (server, client) -> {
            var refused = client.post("/workflows", WORKFLOW);
            assertThat(refused.code()).isEqualTo(401);
            assertThat(refused.header("WWW-Authenticate")).isEqualTo("Bearer");
        });
        assertThat(backend.getWorkflow("wf", "1")).isEmpty();

        JavalinTest.test(appWithToken("s3cret"), (server, client) -> {
            var accepted = client.post("/workflows", WORKFLOW,
                    r -> r.header("Authorization", "Bearer s3cret"));
            assertThat(accepted.code()).isEqualTo(201);
        });
        assertThat(backend.getWorkflow("wf", "1")).isPresent();
    }

    @Test
    void aCustomHookIsUsedAndAFailingHookRefuses() {
        AuthHook onlyAlice = ctx -> "alice".equals(ctx.header("X-User"));
        Javalin app = JamjetServer.createApp(backend, ServerConfig.defaults(), onlyAlice);
        JavalinTest.test(app, (server, client) -> {
            assertThat(client.get("/executions").code()).isEqualTo(401);
            assertThat(client.get("/executions", r -> r.header("X-User", "alice")).code()).isEqualTo(200);
        });

        AuthHook broken = ctx -> {
            throw new IllegalStateException("token store down");
        };
        JavalinTest.test(JamjetServer.createApp(backend, ServerConfig.defaults(), broken), (server, client) ->
                assertThat(client.get("/executions").code()).isEqualTo(401));
    }

    @Test
    void withoutATokenTheServerStaysOpen() {
        JavalinTest.test(JamjetServer.createApp(backend, ServerConfig.defaults()), (server, client) ->
                assertThat(client.get("/executions").code()).isEqualTo(200));
    }

    @Test
    void aBlankTokenIsRejected() {
        assertThatThrownBy(() -> AuthHook.bearerToken(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
