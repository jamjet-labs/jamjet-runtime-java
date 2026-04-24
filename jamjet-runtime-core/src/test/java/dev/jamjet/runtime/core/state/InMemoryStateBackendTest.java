package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.*;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryStateBackendTest {

    private InMemoryStateBackend backend;
    private final ObjectMapper mapper = JamjetJson.shared();

    @BeforeEach
    void setUp() {
        backend = new InMemoryStateBackend();
    }

    // ── 1. Workflow definition CRUD ────────────────────────────────────

    @Test
    void storeAndGetWorkflow() throws StateBackendException {
        ObjectNode ir = mapper.createObjectNode();
        ir.put("entry", "start");

        WorkflowDefinition def = new WorkflowDefinition(
                "summarize", "1.0", ir, Instant.now()
        );

        backend.storeWorkflow(def);

        Optional<WorkflowDefinition> fetched = backend.getWorkflow("summarize", "1.0");
        assertThat(fetched).isPresent();
        assertThat(fetched.get().workflowId()).isEqualTo("summarize");
        assertThat(fetched.get().version()).isEqualTo("1.0");
        assertThat(fetched.get().ir().get("entry").asText()).isEqualTo("start");
        assertThat(fetched.get().tenantId()).isEqualTo("default");

        // Different version returns empty
        assertThat(backend.getWorkflow("summarize", "2.0")).isEmpty();
    }

    // ── 2. Execution create and get ────────────────────────────────────

    @Test
    void createAndGetExecution() throws StateBackendException {
        ExecutionId id = ExecutionId.create();
        ObjectNode input = mapper.createObjectNode().put("prompt", "hello");
        ObjectNode state = mapper.createObjectNode();

        WorkflowExecution exec = new WorkflowExecution(
                id, "summarize", "1.0", WorkflowStatus.PENDING,
                input, state, Instant.now(), Instant.now(), null, null
        );

        backend.createExecution(exec);

        Optional<WorkflowExecution> fetched = backend.getExecution(id);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().executionId()).isEqualTo(id);
        assertThat(fetched.get().status()).isEqualTo(WorkflowStatus.PENDING);
        assertThat(fetched.get().workflowId()).isEqualTo("summarize");
    }

    // ── 3. Execution status update ─────────────────────────────────────

    @Test
    void updateExecutionStatus() throws StateBackendException {
        ExecutionId id = ExecutionId.create();
        ObjectNode input = mapper.createObjectNode();
        ObjectNode state = mapper.createObjectNode();

        WorkflowExecution exec = new WorkflowExecution(
                id, "pipeline", "1.0", WorkflowStatus.PENDING,
                input, state, Instant.now(), Instant.now(), null, null
        );
        backend.createExecution(exec);

        backend.updateExecutionStatus(id, WorkflowStatus.RUNNING);

        WorkflowExecution updated = backend.getExecution(id).orElseThrow();
        assertThat(updated.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(updated.completedAt()).isNull();

        // Transition to terminal sets completedAt
        backend.updateExecutionStatus(id, WorkflowStatus.COMPLETED);
        WorkflowExecution completed = backend.getExecution(id).orElseThrow();
        assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
    }

    // ── 4. Event append and query ──────────────────────────────────────

    @Test
    void appendAndGetEvents() throws StateBackendException {
        ExecutionId execId = ExecutionId.create();
        ObjectNode input = mapper.createObjectNode().put("text", "test");

        Event e1 = Event.create(execId, 1,
                new EventKind.WorkflowStarted("summarize", "1.0", input));
        Event e2 = Event.create(execId, 2,
                new EventKind.NodeScheduled("llm-node", QueueType.MODEL));

        backend.appendEvent(e1);
        backend.appendEvent(e2);

        List<Event> all = backend.getEvents(execId);
        assertThat(all).hasSize(2);

        List<Event> since1 = backend.getEventsSince(execId, 1);
        assertThat(since1).hasSize(1);
        assertThat(since1.get(0).sequence()).isEqualTo(2);

        long latest = backend.latestSequence(execId);
        assertThat(latest).isEqualTo(2);

        // Unknown execution returns 0
        assertThat(backend.latestSequence(ExecutionId.create())).isEqualTo(0);
    }

    // ── 5. Snapshot write and read ─────────────────────────────────────

    @Test
    void snapshotWriteAndRead() throws StateBackendException {
        ExecutionId execId = ExecutionId.create();
        ObjectNode state = mapper.createObjectNode().put("step", 5);

        Snapshot snap = Snapshot.create(execId, 50, state);
        backend.writeSnapshot(snap);

        Optional<Snapshot> latest = backend.latestSnapshot(execId);
        assertThat(latest).isPresent();
        assertThat(latest.get().atSequence()).isEqualTo(50);
        assertThat(latest.get().state().get("step").asInt()).isEqualTo(5);

        // Overwrite with newer snapshot
        ObjectNode state2 = mapper.createObjectNode().put("step", 10);
        Snapshot snap2 = Snapshot.create(execId, 100, state2);
        backend.writeSnapshot(snap2);

        Snapshot overwritten = backend.latestSnapshot(execId).orElseThrow();
        assertThat(overwritten.atSequence()).isEqualTo(100);

        // Unknown execution returns empty
        assertThat(backend.latestSnapshot(ExecutionId.create())).isEmpty();
    }

    // ── 6. Work item lifecycle ─────────────────────────────────────────

    @Test
    void workItemLifecycle() throws StateBackendException {
        ExecutionId execId = ExecutionId.create();
        ObjectNode payload = mapper.createObjectNode().put("model", "gpt-4");

        WorkItem item = new WorkItem(
                UUID.randomUUID(), execId, "llm-node", "model",
                payload, 0, 3, Instant.now(), null, null
        );

        UUID itemId = backend.enqueueWorkItem(item);
        assertThat(itemId).isEqualTo(item.id());

        // Worker-1 claims
        Optional<WorkItem> claimed = backend.claimWorkItem("worker-1", List.of("model"));
        assertThat(claimed).isPresent();
        assertThat(claimed.get().workerId()).isEqualTo("worker-1");
        assertThat(claimed.get().leaseExpiresAt()).isNotNull();

        // Worker-2 finds nothing
        Optional<WorkItem> noClaim = backend.claimWorkItem("worker-2", List.of("model"));
        assertThat(noClaim).isEmpty();

        // Complete
        backend.completeWorkItem(itemId);

        // No more items
        assertThat(backend.claimWorkItem("worker-1", List.of("model"))).isEmpty();
    }

    // ── 7. List executions filter by status ────────────────────────────

    @Test
    void listExecutionsFilterByStatus() throws StateBackendException {
        ObjectNode input = mapper.createObjectNode();
        ObjectNode state = mapper.createObjectNode();

        WorkflowExecution running = new WorkflowExecution(
                ExecutionId.create(), "wf-a", "1.0", WorkflowStatus.RUNNING,
                input, state, Instant.now(), Instant.now(), null, null
        );
        WorkflowExecution completed = new WorkflowExecution(
                ExecutionId.create(), "wf-b", "1.0", WorkflowStatus.COMPLETED,
                input, state, Instant.now(), Instant.now(), Instant.now(), null
        );

        backend.createExecution(running);
        backend.createExecution(completed);

        List<WorkflowExecution> onlyRunning = backend.listExecutions(WorkflowStatus.RUNNING, 10, 0);
        assertThat(onlyRunning).hasSize(1);
        assertThat(onlyRunning.get(0).status()).isEqualTo(WorkflowStatus.RUNNING);

        List<WorkflowExecution> all = backend.listExecutions(null, 10, 0);
        assertThat(all).hasSize(2);
    }
}
