package dev.jamjet.agent.spring;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The approval endpoint authenticates the caller itself (an authenticated principal), acts
 * at the engine with the engine token configured for that principal, never with a bearer the
 * caller sent, and refuses to relay decisions to an engine that runs without authentication.
 * Built through the auto-configuration, as an application gets it.
 */
class JamjetApprovalEndpointSecurityTest {

    WireMockServer wm;

    static final Principal ALICE = () -> "alice";
    static final Principal BOB = () -> "bob";
    static final String APPROVE = "{\"decision\":\"approved\",\"node_id\":\"pay\",\"action_digest\":\"sha256:feed\"}";

    @BeforeEach
    void up() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        // an engine with auth on: an anonymous request is refused
        wm.stubFor(get(urlPathEqualTo("/executions")).willReturn(aResponse().withStatus(401)));
        wm.stubFor(post(urlEqualTo("/executions/ex_1/approve")).willReturn(okJson(
                "{\"execution_id\":\"ex_1\",\"node_id\":\"pay\",\"accepted\":true}")));
        wm.stubFor(get(urlEqualTo("/executions/ex_1/approvals")).willReturn(okJson("{\"pending\":[],\"decided\":[]}")));
    }

    @AfterEach
    void down() {
        wm.stop();
    }

    private void withEndpoint(Consumer<MockMvc> test) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
                        JamjetAgentAutoConfiguration.class, JamjetApprovalEndpointAutoConfiguration.class))
                .withPropertyValues(
                        "jamjet.agent.runtime-url=" + wm.baseUrl(),
                        "jamjet.agent.bearer-token=app-token",
                        "jamjet.agent.worker.enabled=false",
                        "jamjet.agent.approvals.endpoint.enabled=true",
                        "jamjet.agent.approvals.endpoint.approver-tokens.alice=alice-engine-token")
                .run(ctx -> test.accept(MockMvcBuilders.webAppContextSetup(ctx).build()));
    }

    private static MockHttpServletRequestBuilder approve() {
        return MockMvcRequestBuilders.post("/jamjet/executions/ex_1/approve").contentType(MediaType.APPLICATION_JSON).content(APPROVE);
    }

    private static void perform(MockMvc mvc, MockHttpServletRequestBuilder req, int expected) {
        try {
            mvc.perform(req).andExpect(status().is(expected));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aBearerWithoutAnAuthenticatedPrincipalIsRefused() {
        withEndpoint(mvc -> perform(mvc, approve().header("Authorization", "Bearer evil"), 401));
        assertThat(wm.findAll(postRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void theCallersBearerIsNeverForwarded() {
        withEndpoint(mvc -> perform(mvc, approve().principal(ALICE).header("Authorization", "Bearer evil"), 200));
        wm.verify(postRequestedFor(urlEqualTo("/executions/ex_1/approve"))
                .withHeader("Authorization", equalTo("Bearer alice-engine-token")));
        assertThat(wm.findAll(anyRequestedFor(anyUrl()).withHeader("Authorization", equalTo("Bearer evil")))).isEmpty();
    }

    @Test
    void aPrincipalWithNoEngineTokenIsRefused() {
        withEndpoint(mvc -> perform(mvc, approve().principal(BOB), 403));
        assertThat(wm.findAll(postRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void anEngineWithoutAuthenticationIsRefused() {
        wm.stubFor(get(urlPathEqualTo("/executions")).willReturn(okJson("[]")));
        withEndpoint(mvc -> perform(mvc, approve().principal(ALICE), 503));
        assertThat(wm.findAll(postRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void anExecutionIdThatIsNotAnIdIsRefused() {
        withEndpoint(mvc -> perform(mvc, MockMvcRequestBuilders.get("/jamjet/executions/ex 1/approvals").principal(ALICE), 400));
    }
}
