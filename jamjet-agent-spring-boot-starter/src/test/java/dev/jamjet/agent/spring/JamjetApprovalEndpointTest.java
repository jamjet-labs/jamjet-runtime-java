package dev.jamjet.agent.spring;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.jamjet.agent.client.JamjetEngineClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

/**
 * The approval endpoint forwards the caller's own token to the engine, refuses a request
 * without one, and refuses an approval that does not echo the reviewed digest. The engine is
 * a WireMock stub with exact paths.
 */
class JamjetApprovalEndpointTest {

    WireMockServer wm;
    MockMvc mvc;

    @BeforeEach
    void up() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        JamjetEngineClient engine = new JamjetEngineClient(wm.baseUrl(), "app-token", "t1");
        mvc = MockMvcBuilders.standaloneSetup(new JamjetApprovalController(engine)).build();
    }

    @AfterEach
    void down() {
        wm.stop();
    }

    @Test
    void withoutTheCallersTokenNothingReachesTheEngine() throws Exception {
        mvc.perform(request(GET, "/jamjet/executions/ex_1/approvals")).andExpect(status().isUnauthorized());
        mvc.perform(request(POST, "/jamjet/executions/ex_1/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"rejected\",\"node_id\":\"pay\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(POST, "/jamjet/executions/ex_1/approve").header("Authorization", "Basic YWxpY2U=")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"rejected\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(wm.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void listForwardsTheCallersTokenNotTheApps() throws Exception {
        wm.stubFor(get(urlEqualTo("/executions/ex_1/approvals")).willReturn(okJson("""
                {"pending":[{"node_id":"pay","tool_name":"payments.charge","approver":"human",
                 "context":{"action_digest":"sha256:feed"},"sequence":7}],"decided":[]}""")));

        mvc.perform(request(GET, "/jamjet/executions/ex_1/approvals").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending[0].node_id").value("pay"))
                .andExpect(jsonPath("$.pending[0].context.action_digest").value("sha256:feed"));

        wm.verify(getRequestedFor(urlEqualTo("/executions/ex_1/approvals"))
                .withHeader("Authorization", equalTo("Bearer alice"))
                .withHeader("X-Tenant-Id", equalTo("t1")));
    }

    @Test
    void anApprovalWithoutTheReviewedDigestIsRefusedHere() throws Exception {
        mvc.perform(request(POST, "/jamjet/executions/ex_1/approve").header("Authorization", "Bearer alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approved\",\"node_id\":\"pay\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(POST, "/jamjet/executions/ex_1/approve").header("Authorization", "Bearer alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"maybe\",\"node_id\":\"pay\"}"))
                .andExpect(status().isBadRequest());

        assertThat(wm.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void anApprovalIsForwardedAsTheCaller() throws Exception {
        wm.stubFor(post(urlEqualTo("/executions/ex_1/approve")).willReturn(okJson(
                "{\"execution_id\":\"ex_1\",\"node_id\":\"pay\",\"accepted\":true}")));

        mvc.perform(request(POST, "/jamjet/executions/ex_1/approve").header("Authorization", "Bearer alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\",\"node_id\":\"pay\",\"action_digest\":\"sha256:feed\","
                                + "\"comment\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.node_id").value("pay"));

        wm.verify(postRequestedFor(urlEqualTo("/executions/ex_1/approve"))
                .withHeader("Authorization", equalTo("Bearer alice"))
                .withRequestBody(equalToJson(
                        "{\"decision\":\"approved\",\"node_id\":\"pay\",\"action_digest\":\"sha256:feed\",\"comment\":\"ok\"}")));
    }

    @Test
    void engineRefusalsPassThrough() throws Exception {
        wm.stubFor(post(urlEqualTo("/executions/ex_1/approve")).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"action_digest does not match the pending request\"}")));

        mvc.perform(request(POST, "/jamjet/executions/ex_1/approve").header("Authorization", "Bearer alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\",\"node_id\":\"pay\",\"action_digest\":\"sha256:old\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("does not match")));
    }

    @Test
    void anUnreachableEngineIsABadGateway() throws Exception {
        JamjetEngineClient down = new JamjetEngineClient("http://127.0.0.1:1");
        MockMvc offline = MockMvcBuilders.standaloneSetup(new JamjetApprovalController(down)).build();
        offline.perform(request(GET, "/jamjet/executions/ex_1/approvals").header("Authorization", "Bearer alice"))
                .andExpect(status().isBadGateway());
    }

    // -- auto-configuration -------------------------------------------------------

    private static final AutoConfigurations CONFIGS = AutoConfigurations.of(
            JamjetAgentAutoConfiguration.class, JamjetApprovalEndpointAutoConfiguration.class);

    @Test
    void theEndpointIsOffByDefault() {
        new WebApplicationContextRunner().withConfiguration(CONFIGS)
                .withPropertyValues("jamjet.agent.worker.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(JamjetApprovalController.class));
    }

    @Test
    void theEndpointIsThereWhenEnabledInAServletApp() {
        new WebApplicationContextRunner().withConfiguration(CONFIGS)
                .withPropertyValues("jamjet.agent.worker.enabled=false", "jamjet.agent.approvals.endpoint.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(JamjetApprovalController.class));
    }

    @Test
    void theEndpointIsNotThereOutsideAWebApp() {
        new ApplicationContextRunner().withConfiguration(CONFIGS)
                .withPropertyValues("jamjet.agent.worker.enabled=false", "jamjet.agent.approvals.endpoint.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(JamjetApprovalController.class));
    }
}
