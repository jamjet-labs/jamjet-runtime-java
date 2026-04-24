package dev.jamjet.runtime.core.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.QueueType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventSerializationTest {

    private final ObjectMapper mapper = JamjetJson.shared();

    @Test
    void workflowStartedRoundTrip() throws JsonProcessingException {
        var kind = new EventKind.WorkflowStarted(
                "wf-1", "1.0",
                JsonNodeFactory.instance.objectNode().put("input", "hello")
        );
        var event = new Event(
                UUID.randomUUID(),
                ExecutionId.create(),
                1L,
                kind,
                Instant.parse("2026-04-23T10:00:00Z")
        );

        String json = mapper.writeValueAsString(event);

        // Verify type tag
        assertThat(json).contains("\"type\":\"workflow_started\"");
        assertThat(json).contains("\"workflow_id\":\"wf-1\"");
        assertThat(json).contains("\"workflow_version\":\"1.0\"");
        assertThat(json).contains("\"sequence\":1");

        Event deserialized = mapper.readValue(json, Event.class);
        assertThat(deserialized.id()).isEqualTo(event.id());
        assertThat(deserialized.sequence()).isEqualTo(1L);
        assertThat(deserialized.kind()).isInstanceOf(EventKind.WorkflowStarted.class);
        var startedKind = (EventKind.WorkflowStarted) deserialized.kind();
        assertThat(startedKind.workflowId()).isEqualTo("wf-1");
    }

    @Test
    void nodeCompletedWithTelemetryAndProvenance() throws JsonProcessingException {
        var provenance = new ProvenanceMetadata(
                "gpt-4", "0613", 0.95, true,
                "openai", "default",
                List.of("ref-1", "ref-2")
        );
        var kind = new EventKind.NodeCompleted(
                "llm_node",
                JsonNodeFactory.instance.objectNode().put("result", "done"),
                JsonNodeFactory.instance.objectNode().put("status", "complete"),
                1250L,
                "openai",
                "gpt-4",
                500L, 200L,
                "stop",
                0.0035,
                provenance
        );

        String json = mapper.writeValueAsString(kind);

        assertThat(json).contains("\"type\":\"node_completed\"");
        assertThat(json).contains("\"node_id\":\"llm_node\"");
        assertThat(json).contains("\"duration_ms\":1250");
        assertThat(json).contains("\"gen_ai_system\":\"openai\"");
        assertThat(json).contains("\"gen_ai_model\":\"gpt-4\"");
        assertThat(json).contains("\"input_tokens\":500");
        assertThat(json).contains("\"output_tokens\":200");
        assertThat(json).contains("\"finish_reason\":\"stop\"");
        assertThat(json).contains("\"cost_usd\":0.0035");
        assertThat(json).contains("\"model_id\":\"gpt-4\"");
        assertThat(json).contains("\"trust_domain\":\"default\"");
        assertThat(json).contains("\"evidence_refs\"");

        EventKind deserialized = mapper.readValue(json, EventKind.class);
        assertThat(deserialized).isInstanceOf(EventKind.NodeCompleted.class);
        var completed = (EventKind.NodeCompleted) deserialized;
        assertThat(completed.nodeId()).isEqualTo("llm_node");
        assertThat(completed.durationMs()).isEqualTo(1250L);
        assertThat(completed.provenance().modelId()).isEqualTo("gpt-4");
        assertThat(completed.provenance().confidence()).isEqualTo(0.95);
        assertThat(completed.provenance().evidenceRefs()).containsExactly("ref-1", "ref-2");
    }

    @Test
    void approvalReceivedRoundTrip() throws JsonProcessingException {
        var kind = new EventKind.ApprovalReceived(
                "approval_node",
                "user-42",
                ApprovalDecision.APPROVED,
                "Looks good",
                JsonNodeFactory.instance.objectNode().put("approved", true)
        );

        String json = mapper.writeValueAsString(kind);

        assertThat(json).contains("\"type\":\"approval_received\"");
        assertThat(json).contains("\"node_id\":\"approval_node\"");
        assertThat(json).contains("\"user_id\":\"user-42\"");
        assertThat(json).contains("\"decision\":\"approved\"");
        assertThat(json).contains("\"comment\":\"Looks good\"");

        EventKind deserialized = mapper.readValue(json, EventKind.class);
        assertThat(deserialized).isInstanceOf(EventKind.ApprovalReceived.class);
        var approval = (EventKind.ApprovalReceived) deserialized;
        assertThat(approval.decision()).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(approval.userId()).isEqualTo("user-42");
    }

    @Test
    void nullFieldsOmittedFromJson() throws JsonProcessingException {
        var kind = new EventKind.NodeCompleted(
                "n1", null, null, 100L,
                null, null, null, null, null, null, null
        );

        String json = mapper.writeValueAsString(kind);

        assertThat(json).contains("\"node_id\":\"n1\"");
        assertThat(json).contains("\"duration_ms\":100");
        // Null fields should not appear
        assertThat(json).doesNotContain("\"output\"");
        assertThat(json).doesNotContain("\"state_patch\"");
        assertThat(json).doesNotContain("\"gen_ai_system\"");
        assertThat(json).doesNotContain("\"gen_ai_model\"");
        assertThat(json).doesNotContain("\"input_tokens\"");
        assertThat(json).doesNotContain("\"cost_usd\"");
        assertThat(json).doesNotContain("\"provenance\"");
    }

    @Test
    void eventFactoryCreate() {
        var execId = ExecutionId.create();
        var kind = new EventKind.WorkflowStarted("wf-1", "1.0", null);
        var event = Event.create(execId, 1L, kind);

        assertThat(event.id()).isNotNull();
        assertThat(event.executionId()).isEqualTo(execId);
        assertThat(event.sequence()).isEqualTo(1L);
        assertThat(event.kind()).isEqualTo(kind);
        assertThat(event.createdAt()).isNotNull();
    }

    @Test
    void nodeScheduledWithQueueType() throws JsonProcessingException {
        var kind = new EventKind.NodeScheduled("model_node", QueueType.MODEL);
        String json = mapper.writeValueAsString(kind);

        assertThat(json).contains("\"type\":\"node_scheduled\"");
        assertThat(json).contains("\"queue_type\":\"model\"");

        EventKind deserialized = mapper.readValue(json, EventKind.class);
        assertThat(deserialized).isInstanceOf(EventKind.NodeScheduled.class);
        assertThat(((EventKind.NodeScheduled) deserialized).queueType()).isEqualTo(QueueType.MODEL);
    }

    @Test
    void agentToolCompletedRoundTrip() throws JsonProcessingException {
        var kind = new EventKind.AgentToolCompleted(
                "agent_node",
                JsonNodeFactory.instance.objectNode().put("answer", "42"),
                JsonNodeFactory.instance.objectNode().put("model", "gpt-4"),
                0.015,
                3200L,
                5
        );

        String json = mapper.writeValueAsString(kind);
        assertThat(json).contains("\"type\":\"agent_tool_completed\"");
        assertThat(json).contains("\"total_cost\":");
        assertThat(json).contains("\"latency_ms\":3200");
        assertThat(json).contains("\"total_turns\":5");

        EventKind deserialized = mapper.readValue(json, EventKind.class);
        assertThat(deserialized).isInstanceOf(EventKind.AgentToolCompleted.class);
        var completed = (EventKind.AgentToolCompleted) deserialized;
        assertThat(completed.totalTurns()).isEqualTo(5);
    }

    @Test
    void coordinatorDecisionRoundTrip() throws JsonProcessingException {
        var kind = new EventKind.CoordinatorDecision(
                "coord_node", "agent-A", "scoring", "Highest skill match",
                0.92, List.of(), null, 0.001
        );

        String json = mapper.writeValueAsString(kind);
        assertThat(json).contains("\"type\":\"coordinator_decision\"");
        assertThat(json).contains("\"selected\":\"agent-A\"");
        assertThat(json).contains("\"confidence\":0.92");

        EventKind deserialized = mapper.readValue(json, EventKind.class);
        assertThat(deserialized).isInstanceOf(EventKind.CoordinatorDecision.class);
    }
}
