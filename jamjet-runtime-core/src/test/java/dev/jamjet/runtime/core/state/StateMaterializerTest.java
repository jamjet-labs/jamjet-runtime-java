package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.QueueType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.ApprovalDecision;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.event.EventKind.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StateMaterializerTest {

    private static final ObjectMapper mapper = JamjetJson.shared();

    @Test
    void statePatchApplied() {
        ObjectNode base = mapper.createObjectNode().put("x", 1).put("y", 2);
        ObjectNode patch = mapper.createObjectNode().put("x", 10);

        ExecutionId execId = ExecutionId.create();
        List<Event> events = List.of(
                Event.create(execId, 1, new NodeCompleted(
                        "a", mapper.createObjectNode(), patch, 100,
                        null, null, null, null, null, null, null))
        );

        StateMaterializer.MaterializedState result = StateMaterializer.applyEvents(base, events);

        assertThat(result.currentState().get("x").asInt()).isEqualTo(10);
        assertThat(result.currentState().get("y").asInt()).isEqualTo(2);
    }

    @Test
    void nullPatchRemovesKey() {
        ObjectNode base = mapper.createObjectNode().put("a", 1).put("b", 2);
        ObjectNode patch = mapper.createObjectNode();
        patch.putNull("b");

        ExecutionId execId = ExecutionId.create();
        List<Event> events = List.of(
                Event.create(execId, 1, new NodeCompleted(
                        "n1", mapper.createObjectNode(), patch, 50,
                        null, null, null, null, null, null, null))
        );

        StateMaterializer.MaterializedState result = StateMaterializer.applyEvents(base, events);

        assertThat(result.currentState().get("a").asInt()).isEqualTo(1);
        assertThat(result.currentState().has("b")).isFalse();
    }

    @Test
    void fullWorkflowLifecycle() {
        ObjectNode base = mapper.createObjectNode().put("count", 0);
        ObjectNode output = mapper.createObjectNode().put("result", "done");
        ObjectNode patch = mapper.createObjectNode().put("count", 42);
        ObjectNode finalState = mapper.createObjectNode();

        ExecutionId execId = ExecutionId.create();
        List<Event> events = List.of(
                Event.create(execId, 1, new WorkflowStarted("wf", "1.0", base)),
                Event.create(execId, 2, new NodeScheduled("a", QueueType.MODEL)),
                Event.create(execId, 3, new NodeCompleted(
                        "a", output, patch, 200,
                        null, null, null, null, null, null, null)),
                Event.create(execId, 4, new WorkflowCompleted(finalState))
        );

        StateMaterializer.MaterializedState result = StateMaterializer.applyEvents(base, events);

        assertThat(result.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.activeNodes()).isEmpty();
        assertThat(result.completedNodes()).containsKey("a");
        assertThat(result.completedNodes().get("a").get("result").asText()).isEqualTo("done");
        assertThat(result.lastSequence()).isEqualTo(4);
        assertThat(result.currentState().get("count").asInt()).isEqualTo(42);
    }

    @Test
    void interruptPausesExecution() {
        ObjectNode base = mapper.createObjectNode();

        ExecutionId execId = ExecutionId.create();
        List<Event> events = List.of(
                Event.create(execId, 1, new WorkflowStarted("wf", "1.0", base)),
                Event.create(execId, 2, new InterruptRaised("n1", "needs review", base))
        );

        StateMaterializer.MaterializedState result = StateMaterializer.applyEvents(base, events);

        assertThat(result.status()).isEqualTo(WorkflowStatus.PAUSED);
    }

    @Test
    void approvalResumesExecution() {
        ObjectNode base = mapper.createObjectNode().put("val", 1);
        ObjectNode approvalPatch = mapper.createObjectNode().put("val", 99);

        ExecutionId execId = ExecutionId.create();
        List<Event> events = List.of(
                Event.create(execId, 1, new WorkflowStarted("wf", "1.0", base)),
                Event.create(execId, 2, new InterruptRaised("n1", "needs approval", base)),
                Event.create(execId, 3, new ApprovalReceived(
                        "n1", "user-1", ApprovalDecision.APPROVED, "looks good", approvalPatch))
        );

        StateMaterializer.MaterializedState result = StateMaterializer.applyEvents(base, events);

        assertThat(result.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(result.currentState().get("val").asInt()).isEqualTo(99);
    }

    @Test
    void nestedMergePatch() {
        // RFC 7396 test: {a: {b: 1, c: 2}, d: 3} + {a: {b: 10, c: null}, e: 5}
        ObjectNode target = mapper.createObjectNode();
        ObjectNode inner = target.putObject("a");
        inner.put("b", 1);
        inner.put("c", 2);
        target.put("d", 3);

        ObjectNode patch = mapper.createObjectNode();
        ObjectNode patchInner = patch.putObject("a");
        patchInner.put("b", 10);
        patchInner.putNull("c");
        patch.put("e", 5);

        StateMaterializer.jsonMergePatch(target, patch);

        // a.b = 10
        assertThat(target.get("a").get("b").asInt()).isEqualTo(10);
        // a.c removed
        assertThat(target.get("a").has("c")).isFalse();
        // d unchanged
        assertThat(target.get("d").asInt()).isEqualTo(3);
        // e added
        assertThat(target.get("e").asInt()).isEqualTo(5);
    }

    @Test
    void materializeWithBackend() throws StateBackendException {
        InMemoryStateBackend backend = new InMemoryStateBackend();
        ExecutionId execId = ExecutionId.create();

        ObjectNode initialInput = mapper.createObjectNode().put("x", 1);
        ObjectNode currentState = mapper.createObjectNode().put("x", 1);

        WorkflowExecution exec = new WorkflowExecution(
                execId, "wf", "1.0", WorkflowStatus.PENDING,
                initialInput, currentState, Instant.now(), Instant.now(), null, null
        );
        backend.createExecution(exec);

        // Append events
        ObjectNode patch = mapper.createObjectNode().put("x", 99);
        backend.appendEvent(Event.create(execId, 1,
                new WorkflowStarted("wf", "1.0", initialInput)));
        backend.appendEvent(Event.create(execId, 2,
                new NodeCompleted("n1", mapper.createObjectNode(), patch, 100,
                        null, null, null, null, null, null, null)));

        // Materialize
        StateMaterializer.MaterializedState result =
                StateMaterializer.materialize(backend, execId);

        assertThat(result.currentState().get("x").asInt()).isEqualTo(99);
        assertThat(result.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(result.lastSequence()).isEqualTo(2);
        assertThat(result.completedNodes()).containsKey("n1");
    }
}
