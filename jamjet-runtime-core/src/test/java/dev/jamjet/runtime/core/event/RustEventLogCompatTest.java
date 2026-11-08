package dev.jamjet.runtime.core.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.JamjetJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An event log written by the current Rust runtime ({@code runtime/state/src/event.rs},
 * serde {@code tag = "type"}, snake_case) must load. One kind the Java side does not know
 * must not fail the whole log.
 */
class RustEventLogCompatTest {

    private static final String EXEC = "\"execution_id\":\"0b7c5b8e-1d2f-4c3a-9e8f-112233445566\"";
    private static final String AT = "\"created_at\":\"2026-11-07T10:00:00.123456Z\"";

    private static String event(int seq, String kind) {
        return "{\"id\":\"00000000-0000-4000-8000-0000000000" + String.format("%02d", seq) + "\","
                + EXEC + ",\"sequence\":" + seq + ",\"kind\":" + kind + "," + AT + "}";
    }

    static final String RUST_LOG = "[" + String.join(",",
            event(1, "{\"type\":\"workflow_started\",\"workflow_id\":\"wf\",\"workflow_version\":\"1\","
                    + "\"initial_input\":{},\"initiated_by\":\"token:7\"}"),
            event(2, "{\"type\":\"node_parked\",\"node_id\":\"llm\",\"attempt\":2,"
                    + "\"retry_after\":\"2026-11-07T10:01:00+00:00\"}"),
            event(3, "{\"type\":\"agent_step_admitted\",\"node_id\":\"llm\","
                    + "\"work_item_id\":\"9a1b2c3d-0000-4000-8000-000000000001\",\"agents\":[\"planner\"],"
                    + "\"tool_calls\":1,\"approval_sequence\":5}"),
            event(4, "{\"type\":\"tool_approval_required\",\"node_id\":\"pay\",\"tool_name\":\"payments.charge\","
                    + "\"approver\":\"human\",\"context\":{\"action_digest\":\"sha256:ab\"}}"),
            event(5, "{\"type\":\"approval_escalated\",\"node_id\":\"pay\",\"request_sequence\":4,"
                    + "\"escalate_at\":\"2026-11-07T10:05:00Z\",\"expires_at\":\"2026-11-07T11:00:00Z\"}"),
            event(6, "{\"type\":\"approval_expired\",\"node_id\":\"pay\",\"request_sequence\":4,"
                    + "\"expires_at\":\"2026-11-07T11:00:00Z\",\"principal\":\"system:deadline\"}"),
            event(7, "{\"type\":\"approval_received\",\"node_id\":\"pay\",\"user_id\":\"ops\","
                    + "\"decision\":\"approved\",\"comment\":null,\"state_patch\":null,"
                    + "\"principal\":\"token:9\",\"approved_digest\":\"sha256:ab\",\"request_sequence\":4}"),
            event(8, "{\"type\":\"audit_seal_failed\",\"event_id\":\"00000000-0000-4000-8000-000000000004\","
                    + "\"sequence\":4,\"event_type\":\"tool_approval_required\",\"error\":\"disk full\"}"),
            event(9, "{\"type\":\"segment_boundary\",\"segment_number\":2,"
                    + "\"next_execution_id\":\"exec_aa11\"}"),
            event(10, "{\"type\":\"run_stalled\",\"reason\":\"unknown event kind in log\"}"),
            event(11, "{\"type\":\"node_completed\",\"node_id\":\"llm\",\"output\":{},\"state_patch\":{},"
                    + "\"duration_ms\":12,\"idempotency_key\":\"ik-1\"}"),
            event(12, "{\"type\":\"a_kind_from_a_later_release\",\"anything\":[1,2,3]}"),
            event(13, "{\"type\":\"unknown\"}")
    ) + "]";

    @Test
    void theCurrentRustEventLogLoads() throws Exception {
        ObjectMapper mapper = JamjetJson.shared();
        List<Event> events = mapper.readValue(RUST_LOG, new TypeReference<List<Event>>() {});

        assertThat(events).extracting(e -> e.kind().getClass().getSimpleName()).containsExactly(
                "WorkflowStarted",
                "NodeParked",
                "AgentStepAdmitted",
                "ToolApprovalRequired",
                "ApprovalEscalated",
                "ApprovalExpired",
                "ApprovalReceived",
                "AuditSealFailed",
                "SegmentBoundary",
                "RunStalled",
                "NodeCompleted",
                "Unknown",
                "Unknown");
    }

    @Test
    void fieldsTheRustRuntimeAddedAreRead() throws Exception {
        List<Event> events = JamjetJson.shared().readValue(RUST_LOG, new TypeReference<List<Event>>() {});

        var started = (EventKind.WorkflowStarted) events.get(0).kind();
        assertThat(started.initiatedBy()).isEqualTo("token:7");

        var admitted = (EventKind.AgentStepAdmitted) events.get(2).kind();
        assertThat(admitted.agents()).containsExactly("planner");
        assertThat(admitted.toolCalls()).isEqualTo(1);
        assertThat(admitted.approvalSequence()).isEqualTo(5L);

        var expired = (EventKind.ApprovalExpired) events.get(5).kind();
        assertThat(expired.requestSequence()).isEqualTo(4L);
        assertThat(expired.principal()).isEqualTo("system:deadline");

        var received = (EventKind.ApprovalReceived) events.get(6).kind();
        assertThat(received.decision()).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(received.principal()).isEqualTo("token:9");
        assertThat(received.approvedDigest()).isEqualTo("sha256:ab");
        assertThat(received.requestSequence()).isEqualTo(4L);

        var completed = (EventKind.NodeCompleted) events.get(10).kind();
        assertThat(completed.idempotencyKey()).isEqualTo("ik-1");
    }

    @Test
    void anUnknownKindWritesBackAsUnknown() throws Exception {
        ObjectMapper mapper = JamjetJson.shared();
        EventKind kind = mapper.readValue("{\"type\":\"later_kind\",\"x\":1}", EventKind.class);
        assertThat(kind).isInstanceOf(EventKind.Unknown.class);
        assertThat(mapper.writeValueAsString(kind)).isEqualTo("{\"type\":\"unknown\"}");
    }

    @Test
    void newKindsRoundTrip() throws Exception {
        ObjectMapper mapper = JamjetJson.shared();
        List<Event> events = mapper.readValue(RUST_LOG, new TypeReference<List<Event>>() {});
        for (Event event : events) {
            String written = mapper.writeValueAsString(event);
            Event again = mapper.readValue(written, Event.class);
            if (event.kind() instanceof EventKind.Unknown) {
                // as in Rust: an unknown kind is written back as {"type":"unknown"}
                assertThat(mapper.writeValueAsString(event.kind())).isEqualTo("{\"type\":\"unknown\"}");
                assertThat(again.kind()).isInstanceOf(EventKind.Unknown.class);
            } else {
                assertThat(again.kind()).isEqualTo(event.kind());
            }
        }
    }

    @Test
    void anUnknownValueInsideAKnownKindDoesNotFailTheLog() throws Exception {
        String log = "[" + event(1, "{\"type\":\"node_scheduled\",\"node_id\":\"n\",\"queue_type\":\"gpu_tool\"}") + ","
                + event(2, "{\"type\":\"approval_received\",\"node_id\":\"n\",\"user_id\":\"ops\","
                + "\"decision\":\"deferred\",\"comment\":null,\"state_patch\":null}") + "]";
        List<Event> events = JamjetJson.shared().readValue(log, new TypeReference<List<Event>>() {});
        assertThat(events).hasSize(2);
    }

    @Test
    void anUnknownKindKeepsItsOriginalTypeAndFields() throws Exception {
        EventKind kind = JamjetJson.shared().readValue(
                "{\"type\":\"grant_revoked\",\"node_id\":\"pay\",\"scope\":[\"payments\"]}", EventKind.class);
        var unknown = (EventKind.Unknown) kind;
        assertThat(unknown.originalType()).isEqualTo("grant_revoked");
        assertThat(unknown.fields().path("node_id").asText()).isEqualTo("pay");
        assertThat(unknown.fields().path("scope").get(0).asText()).isEqualTo("payments");
    }

    @Test
    void aKnownKindLoadsWithAStrictMapperToo() throws Exception {
        ObjectMapper strict = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .build();
        EventKind kind = strict.readValue("{\"type\":\"run_stalled\",\"reason\":\"r\"}", EventKind.class);
        assertThat(kind).isEqualTo(new EventKind.RunStalled("r"));
        assertThat(strict.writeValueAsString(kind)).isEqualTo("{\"type\":\"run_stalled\",\"reason\":\"r\"}");
    }

    @Test
    void anUnknownDecisionAuthorisesNothing() throws Exception {
        String log = "[" + event(1, "{\"type\":\"approval_received\",\"node_id\":\"n\",\"user_id\":\"ops\","
                + "\"decision\":\"deferred\",\"comment\":null,\"state_patch\":null}") + "]";
        List<Event> events = JamjetJson.shared().readValue(log, new TypeReference<List<Event>>() {});
        assertThat(((EventKind.ApprovalReceived) events.get(0).kind()).decision()).isEqualTo(ApprovalDecision.UNKNOWN);
        assertThat(dev.jamjet.runtime.core.scheduler.RunnableNodeDetector.hasUnreadableEvent(events)).isTrue();
        assertThat(dev.jamjet.runtime.core.state.StateMaterializer.applyEvents(null, events).indeterminate()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApprovalDecision.fromValue("deferred"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMaterializerReportsAnUnreadableLogAsIndeterminate() throws Exception {
        List<Event> events = JamjetJson.shared().readValue(RUST_LOG, new TypeReference<List<Event>>() {});
        assertThat(dev.jamjet.runtime.core.state.StateMaterializer.applyEvents(null, events).indeterminate()).isTrue();
        assertThat(dev.jamjet.runtime.core.state.StateMaterializer.applyEvents(null, events.subList(0, 11)).indeterminate())
                .isFalse();
    }
}
