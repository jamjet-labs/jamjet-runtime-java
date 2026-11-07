package dev.jamjet.agent.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Approval calls against a stub of the engine's routes ({@code runtime/api/src/routes.rs}):
 * exact paths, exact bodies, and the token each call carries.
 */
class JamjetEngineClientApprovalsTest {

    /** The event-log shape of {@code GET /executions/:id/approvals} (approvals_view). */
    static final String APPROVALS = """
            {"pending":[{"node_id":"pay","tool_name":"payments.charge","approver":"human",
              "context":{"action_digest":"sha256:feed","amount":120},"sequence":7,
              "expires_at":"2026-11-07T11:00:00+00:00"},
             {"node_id":"dispatch","tool_name":"agent_tool_dispatch","approver":"human",
              "context":{"calls_hash":"sha256:ca11"},"sequence":9}],
             "decided":[{"node_id":"lookup","status":"approved","user_id":"ops","sequence":4,
              "approved_digest":"sha256:0ld"},
             {"node_id":"wire","status":"rejected","user_id":"ops","comment":"no","sequence":5}]}""";

    WireMockServer wm;

    @BeforeEach
    void up() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void down() {
        wm.stop();
    }

    @Test
    void listApprovalsReadsPendingAndDecided() {
        wm.stubFor(get(urlEqualTo("/executions/ex_1/approvals")).willReturn(okJson(APPROVALS)));

        ApprovalList list = new JamjetEngineClient(wm.baseUrl(), "app-token", "t1").listApprovals("ex_1");

        assertThat(list.pending()).hasSize(2);
        PendingApproval pay = list.pending().get(0);
        assertThat(pay.nodeId()).isEqualTo("pay");
        assertThat(pay.toolName()).isEqualTo("payments.charge");
        assertThat(pay.sequence()).isEqualTo(7L);
        assertThat(pay.expiresAt()).isEqualTo("2026-11-07T11:00:00+00:00");
        assertThat(pay.binding()).isEqualTo("sha256:feed");
        assertThat(list.pending().get(1).binding()).isEqualTo("sha256:ca11");
        assertThat(list.decided()).extracting(DecidedApproval::status).containsExactly("approved", "rejected");
        assertThat(list.decided().get(0).approvedDigest()).isEqualTo("sha256:0ld");
        wm.verify(getRequestedFor(urlEqualTo("/executions/ex_1/approvals"))
                .withHeader("Authorization", equalTo("Bearer app-token"))
                .withHeader("X-Tenant-Id", equalTo("t1")));
    }

    @Test
    void approveEchoesTheReviewedRequestsDigestWithTheDecidersToken() {
        wm.stubFor(get(urlEqualTo("/executions/ex_1/approvals")).willReturn(okJson(APPROVALS)));
        wm.stubFor(post(urlEqualTo("/executions/ex_1/approve")).willReturn(okJson(
                "{\"execution_id\":\"ex_1\",\"node_id\":\"pay\",\"accepted\":true}")));
        JamjetEngineClient app = new JamjetEngineClient(wm.baseUrl(), "app-token", "t1");

        PendingApproval reviewed = app.listApprovals("ex_1").pending().get(0);
        ApprovalResult result = app.withBearerToken("alice-token").approve("ex_1", reviewed, "looks right");

        assertThat(result.accepted()).isTrue();
        assertThat(result.nodeId()).isEqualTo("pay");
        wm.verify(postRequestedFor(urlEqualTo("/executions/ex_1/approve"))
                .withHeader("Authorization", equalTo("Bearer alice-token"))
                .withHeader("X-Tenant-Id", equalTo("t1"))
                .withRequestBody(equalToJson("""
                        {"decision":"approved","node_id":"pay","action_digest":"sha256:feed",
                         "comment":"looks right"}""")));
    }

    @Test
    void approvingAnAgentDispatchEchoesItsCallsHash() {
        wm.stubFor(get(urlEqualTo("/executions/ex_1/approvals")).willReturn(okJson(APPROVALS)));
        wm.stubFor(post(urlEqualTo("/executions/ex_1/approve")).willReturn(okJson(
                "{\"execution_id\":\"ex_1\",\"node_id\":\"dispatch\",\"accepted\":true}")));
        JamjetEngineClient client = new JamjetEngineClient(wm.baseUrl());

        client.approve("ex_1", client.listApprovals("ex_1").pending().get(1), null);

        wm.verify(postRequestedFor(urlEqualTo("/executions/ex_1/approve"))
                .withHeader("Authorization", absent())
                .withRequestBody(equalToJson("""
                        {"decision":"approved","node_id":"dispatch","action_digest":"sha256:ca11"}""")));
    }

    @Test
    void rejectSendsNoDigest() {
        wm.stubFor(post(urlEqualTo("/executions/ex_1/approve")).willReturn(okJson(
                "{\"execution_id\":\"ex_1\",\"node_id\":\"pay\",\"accepted\":true}")));

        new JamjetEngineClient(wm.baseUrl()).reject("ex_1", "pay", "amount too high");

        wm.verify(postRequestedFor(urlEqualTo("/executions/ex_1/approve"))
                .withRequestBody(equalToJson("""
                        {"decision":"rejected","node_id":"pay","comment":"amount too high"}""")));
    }

    @Test
    void aSupersededRequestSurfacesAsAConflict() {
        wm.stubFor(post(urlEqualTo("/executions/ex_1/approve")).willReturn(aResponse().withStatus(409)
                .withBody("{\"error\":\"action_digest does not match the pending request\"}")));

        JamjetHttpException e = catchThrowableOfType(
                () -> new JamjetEngineClient(wm.baseUrl()).approve("ex_1", "pay", "sha256:stale", null),
                JamjetHttpException.class);

        assertThat(e.isConflict()).isTrue();
        assertThat(e.getMessage()).contains("does not match");
    }

    @Test
    void externalEventAndAuditUseTheEngineRoutes() {
        wm.stubFor(post(urlEqualTo("/executions/ex_1/external-event"))
                .willReturn(okJson("{\"execution_id\":\"ex_1\",\"accepted\":true}")));
        wm.stubFor(get(urlEqualTo("/audit?limit=200&offset=0"))
                .willReturn(okJson("{\"items\":[{\"sequence\":1e+16}],\"total\":1}")));
        JamjetEngineClient client = new JamjetEngineClient(wm.baseUrl());

        client.sendExternalEvent("ex_1", "order-42", Map.of("paid", true));
        String audit = client.auditLogText(200, 0);

        wm.verify(postRequestedFor(urlEqualTo("/executions/ex_1/external-event"))
                .withRequestBody(equalToJson("{\"correlation_key\":\"order-42\",\"payload\":{\"paid\":true}}")));
        // returned as written: the number keeps its spelling
        assertThat(audit).contains("1e+16");
    }
}
