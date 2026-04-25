package dev.jamjet.runtime.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRoutesTest {

    private final Javalin app = JamjetServer.createApp(
            new InMemoryStateBackend(), ServerConfig.defaults());

    @Test
    void createTenantReturns201() {
        JavalinTest.test(app, (server, client) -> {
            var body = """
                    {"id": "tenant-1", "name": "Test Tenant"}
                    """;
            var response = client.post("/tenants", body);
            assertThat(response.code()).isEqualTo(201);
            var responseBody = response.body().string();
            assertThat(responseBody).contains("tenant-1");
            assertThat(responseBody).contains("Test Tenant");
        });
    }

    @Test
    void listTenantsReturnsAll() throws Exception {
        JavalinTest.test(app, (server, client) -> {
            // Create two tenants
            client.post("/tenants", """
                    {"id": "t1", "name": "Tenant 1"}
                    """);
            client.post("/tenants", """
                    {"id": "t2", "name": "Tenant 2"}
                    """);

            var response = client.get("/tenants");
            assertThat(response.code()).isEqualTo(200);
            var responseBody = response.body().string();
            assertThat(responseBody).contains("t1");
            assertThat(responseBody).contains("t2");
        });
    }

    @Test
    void getTenantByIdReturns200() throws Exception {
        JavalinTest.test(app, (server, client) -> {
            client.post("/tenants", """
                    {"id": "tenant-get", "name": "Get Test"}
                    """);

            var response = client.get("/tenants/tenant-get");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("tenant-get");
        });
    }

    @Test
    void getTenantNotFoundReturns404() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/tenants/nonexistent");
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void createTenantMissingIdReturns400() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/tenants", """
                    {"name": "No ID"}
                    """);
            assertThat(response.code()).isEqualTo(400);
        });
    }
}
