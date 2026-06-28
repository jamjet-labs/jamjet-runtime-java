package dev.jamjet.agent.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.jamjet.runtime.core.ir.NodeDef;
import dev.jamjet.runtime.core.ir.NodeKind;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Hermetic round-trip proof for {@link JamjetEngineClient} against a WireMock stub
 * of the engine's BARE routes. Asserts both the typed results AND the exact request
 * bodies/headers WireMock received, so the test proves the wire shape (snake_case
 * keys, the lease fence, bare paths), not just that the code runs.
 */
class JamjetEngineClientTest {

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

    /** A minimal agent-loop-shaped IR: a single Model start node (never a tool node). */
    private static WorkflowIr demoIr() {
        var model = new NodeKind.Model("gpt-4o", null, null, "you are helpful");
        var node = new NodeDef("model_0", model, null, null, null, null, null, null);
        return new WorkflowIr(
                "demo", "v1", "demo agent", null, null,
                "model_0",                       // startNode = the Model node (M2)
                Map.of("model_0", node),
                List.of(),
                null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    void createStartPollToCompleted() {
        wm.stubFor(post(urlEqualTo("/workflows"))
                .willReturn(okJson("{\"workflow_id\":\"wf_123\",\"version\":\"v1\"}")));
        wm.stubFor(post(urlEqualTo("/executions"))
                .willReturn(okJson("{\"execution_id\":\"ex_123\",\"status\":\"Running\"}")));
        // First poll -> Running, second poll -> Completed (WireMock scenario transition).
        wm.stubFor(get(urlEqualTo("/executions/ex_123")).inScenario("poll")
                .whenScenarioStateIs(STARTED)
                .willReturn(okJson("{\"execution_id\":\"ex_123\",\"status\":\"Running\"}"))
                .willSetStateTo("done"));
        wm.stubFor(get(urlEqualTo("/executions/ex_123")).inScenario("poll")
                .whenScenarioStateIs("done")
                .willReturn(okJson(
                        "{\"execution_id\":\"ex_123\",\"status\":\"Completed\","
                                + "\"current_state\":{\"answer\":\"42\"}}")));

        var client = new JamjetEngineClient(wm.baseUrl(), "tkn", "t1");

        CreateWorkflowResult wf = client.createWorkflow(demoIr());
        assertThat(wf.workflowId()).isEqualTo("wf_123");
        assertThat(wf.version()).isEqualTo("v1");

        StartExecutionResult exec = client.startExecution("wf_123", Map.of("prompt", "hello"), "v1");
        assertThat(exec.executionId()).isEqualTo("ex_123");

        ExecutionState state = null;
        for (int i = 0; i < 10; i++) {
            state = client.getExecution("ex_123");
            if ("Completed".equals(state.status())) {
                break;
            }
        }
        assertThat(state).isNotNull();
        assertThat(state.status()).isEqualTo("Completed");
        assertThat(state.currentState()).containsEntry("answer", "42");

        // Wire shape: bare path, auth + tenant headers, typed IR serialized snake_case.
        wm.verify(postRequestedFor(urlEqualTo("/workflows"))
                .withHeader("Authorization", equalTo("Bearer tkn"))
                .withHeader("X-Tenant-Id", equalTo("t1"))
                .withRequestBody(matchingJsonPath("$.ir.workflow_id", equalTo("demo")))
                .withRequestBody(matchingJsonPath("$.ir.start_node", equalTo("model_0"))));
        // Wire shape: the version key is workflow_version (not version), input nested.
        wm.verify(postRequestedFor(urlEqualTo("/executions"))
                .withRequestBody(matchingJsonPath("$.workflow_id", equalTo("wf_123")))
                .withRequestBody(matchingJsonPath("$.workflow_version", equalTo("v1")))
                .withRequestBody(matchingJsonPath("$.input.prompt", equalTo("hello"))));
    }

    @Test
    void claimThreadsLeaseFenceThroughHeartbeatAndComplete() {
        wm.stubFor(post(urlEqualTo("/work-items/claim")).willReturn(okJson(
                "{\"claimed\":true,\"work_item\":{"
                        + "\"id\":\"wi_1\",\"execution_id\":\"ex_123\",\"node_id\":\"tool_0\","
                        + "\"queue_type\":\"java_tool\","
                        + "\"payload\":{\"class\":\"C\",\"method\":\"m\",\"input\":{\"x\":1}},"
                        + "\"attempt\":1,\"lease_fence\":7}}")));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/heartbeat")).willReturn(ok()));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/complete")).willReturn(ok()));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/fail")).willReturn(ok()));

        var client = new JamjetEngineClient(wm.baseUrl());

        Optional<ClaimedWorkItem> claimed = client.claimWorkItem("w1", List.of("java_tool"));
        assertThat(claimed).isPresent();
        ClaimedWorkItem wi = claimed.get();
        assertThat(wi.id()).isEqualTo("wi_1");
        assertThat(wi.executionId()).isEqualTo("ex_123");
        assertThat(wi.nodeId()).isEqualTo("tool_0");
        assertThat(wi.queueType()).isEqualTo("java_tool");
        assertThat(wi.attempt()).isEqualTo(1);
        assertThat(wi.leaseFence()).isEqualTo(7L);
        assertThat(wi.payload()).containsEntry("method", "m");

        client.heartbeatWorkItem(wi.id(), "w1", wi.leaseFence());
        client.completeWorkItem(wi.id(), wi.executionId(), wi.nodeId(),
                Map.of("result", "ok"), Map.of("done", true), 12L, null, "stop", wi.leaseFence());
        client.failWorkItem(wi.id(), "boom");

        wm.verify(postRequestedFor(urlEqualTo("/work-items/claim"))
                .withRequestBody(matchingJsonPath("$.worker_id", equalTo("w1")))
                .withRequestBody(matchingJsonPath("$.queue_types[0]", equalTo("java_tool"))));
        // The fence is echoed on heartbeat...
        wm.verify(postRequestedFor(urlEqualTo("/work-items/wi_1/heartbeat"))
                .withRequestBody(matchingJsonPath("$.worker_id", equalTo("w1")))
                .withRequestBody(matchingJsonPath("$.lease_fence", equalTo("7"))));
        // ...and in the complete body (the fenced completion path).
        wm.verify(postRequestedFor(urlEqualTo("/work-items/wi_1/complete"))
                .withRequestBody(matchingJsonPath("$.lease_fence", equalTo("7")))
                .withRequestBody(matchingJsonPath("$.execution_id", equalTo("ex_123")))
                .withRequestBody(matchingJsonPath("$.node_id", equalTo("tool_0")))
                .withRequestBody(matchingJsonPath("$.duration_ms", equalTo("12")))
                .withRequestBody(matchingJsonPath("$.finish_reason", equalTo("stop")))
                .withRequestBody(matchingJsonPath("$.output.result", equalTo("ok")))
                .withRequestBody(matchingJsonPath("$.state_patch.done", equalTo("true"))));
        wm.verify(postRequestedFor(urlEqualTo("/work-items/wi_1/fail"))
                .withRequestBody(matchingJsonPath("$.error", equalTo("boom"))));
    }

    @Test
    void emptyQueueClaimReturnsEmpty() {
        wm.stubFor(post(urlEqualTo("/work-items/claim"))
                .willReturn(okJson("{\"claimed\":false}")));

        var client = new JamjetEngineClient(wm.baseUrl());

        assertThat(client.claimWorkItem("w1", List.of("java_tool"))).isEmpty();
    }

    @Test
    void completeOmitsLeaseFenceWhenNull() {
        wm.stubFor(post(urlEqualTo("/work-items/wi_nf/complete")).willReturn(ok()));

        var client = new JamjetEngineClient(wm.baseUrl());
        client.completeWorkItem("wi_nf", "ex_1", "n1",
                Map.of("r", "ok"), Map.of(), 1L, null, null, null);

        List<LoggedRequest> reqs = wm.findAll(postRequestedFor(urlEqualTo("/work-items/wi_nf/complete")));
        assertThat(reqs).hasSize(1);
        String body = reqs.get(0).getBodyAsString();
        assertThat(body).doesNotContain("lease_fence");
        assertThat(body).contains("output");
        assertThat(body).contains("duration_ms");
    }

    @Test
    void heartbeatOmitsLeaseFenceWhenNull() {
        wm.stubFor(post(urlEqualTo("/work-items/wi_nf/heartbeat")).willReturn(ok()));

        var client = new JamjetEngineClient(wm.baseUrl());
        // A null fence (legacy unfenced claim) must be omitted from the body, mirroring the
        // nullable contract of completeWorkItem.
        client.heartbeatWorkItem("wi_nf", "w1", null);

        List<LoggedRequest> reqs = wm.findAll(postRequestedFor(urlEqualTo("/work-items/wi_nf/heartbeat")));
        assertThat(reqs).hasSize(1);
        String body = reqs.get(0).getBodyAsString();
        assertThat(body).doesNotContain("lease_fence");
        assertThat(body).contains("worker_id");
    }

    @Test
    void staleFenceCompleteSurfacesAsConflict() {
        wm.stubFor(post(urlEqualTo("/work-items/wi_stale/complete"))
                .willReturn(aResponse().withStatus(409).withBody("{\"error\":\"stale lease fence\"}")));

        var client = new JamjetEngineClient(wm.baseUrl());

        JamjetHttpException ex = catchThrowableOfType(
                JamjetHttpException.class,
                () -> client.completeWorkItem("wi_stale", "ex_1", "n1",
                        Map.of("r", "ok"), Map.of(), 1L, null, null, 1L));

        assertThat(ex).isNotNull();
        assertThat(ex.statusCode()).isEqualTo(409);
        assertThat(ex.isConflict()).isTrue();
        assertThat(ex.body()).contains("stale");
    }
}
