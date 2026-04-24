package dev.jamjet.runtime.core.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.QueueType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.ir.EdgeDef;
import dev.jamjet.runtime.core.ir.NodeDef;
import dev.jamjet.runtime.core.ir.NodeKind;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import dev.jamjet.runtime.core.state.WorkItem;
import dev.jamjet.runtime.core.state.WorkflowDefinition;
import dev.jamjet.runtime.core.state.WorkflowExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    private InMemoryStateBackend backend;
    private Scheduler scheduler;
    private final ObjectMapper mapper = JamjetJson.shared();

    @BeforeEach
    void setUp() {
        backend = new InMemoryStateBackend();
        scheduler = new Scheduler(backend);
    }

    /**
     * Simple workflow: A → B with Tool nodes.
     */
    private WorkflowIr simpleWorkflow() {
        var nodeA = new NodeDef("a", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);
        var nodeB = new NodeDef("b", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);
        return new WorkflowIr(
                "simple", "1", "Simple", null, null, "a",
                Map.of("a", nodeA, "b", nodeB),
                List.of(new EdgeDef("a", "b", null)),
                Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                null, null, null, null, null
        );
    }

    private void registerWorkflow(WorkflowIr ir) throws Exception {
        JsonNode irJson = mapper.valueToTree(ir);
        backend.storeWorkflow(new WorkflowDefinition(
                ir.workflowId(), ir.version(), irJson, Instant.now()));
    }

    private ExecutionId startExecution(String workflowId, String version) throws Exception {
        ExecutionId execId = ExecutionId.create();
        backend.createExecution(new WorkflowExecution(
                execId, workflowId, version, WorkflowStatus.RUNNING,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                Instant.now(), Instant.now(), null, null
        ));
        backend.appendEvent(Event.create(execId, 1,
                new EventKind.WorkflowStarted(workflowId, version,
                        JsonNodeFactory.instance.objectNode())));
        return execId;
    }

    @Test
    void tickDispatchesStartNode() throws Exception {
        var ir = simpleWorkflow();
        registerWorkflow(ir);
        ExecutionId execId = startExecution("simple", "1");

        scheduler.tick();

        // Should have NodeScheduled event for "a"
        var events = backend.getEvents(execId);
        boolean hasNodeScheduled = events.stream()
                .anyMatch(e -> e.kind() instanceof EventKind.NodeScheduled ns
                        && ns.nodeId().equals("a"));
        assertTrue(hasNodeScheduled, "Should emit NodeScheduled for 'a'");

        // Should have a claimable work item
        var claimed = backend.claimWorkItem("test-worker", List.of("tool"));
        assertTrue(claimed.isPresent(), "Work item should be claimable");
        assertEquals("a", claimed.get().nodeId());
    }

    @Test
    void tickSkipsNonRunningExecutions() throws Exception {
        var ir = simpleWorkflow();
        registerWorkflow(ir);
        ExecutionId execId = ExecutionId.create();
        backend.createExecution(new WorkflowExecution(
                execId, "simple", "1", WorkflowStatus.COMPLETED,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                Instant.now(), Instant.now(), Instant.now(), null
        ));

        scheduler.tick();

        // No new events should be appended (only the ones we put in initially — none)
        var events = backend.getEvents(execId);
        assertTrue(events.isEmpty(), "No events should be appended for completed execution");
    }

    @Test
    void tickDispatchesNextNodeAfterCompletion() throws Exception {
        var ir = simpleWorkflow();
        registerWorkflow(ir);
        ExecutionId execId = startExecution("simple", "1");

        // First tick dispatches "a"
        scheduler.tick();

        // Simulate completion of "a"
        long seq = backend.latestSequence(execId) + 1;
        backend.appendEvent(Event.create(execId, seq,
                new EventKind.NodeStarted("a", "w1", 1)));
        backend.appendEvent(Event.create(execId, seq + 1,
                new EventKind.NodeCompleted("a",
                        JsonNodeFactory.instance.objectNode(), null, 100,
                        null, null, null, null, null, null, null)));

        // Second tick should dispatch "b"
        scheduler.tick();

        var events = backend.getEvents(execId);
        boolean hasBScheduled = events.stream()
                .anyMatch(e -> e.kind() instanceof EventKind.NodeScheduled ns
                        && ns.nodeId().equals("b"));
        assertTrue(hasBScheduled, "Should emit NodeScheduled for 'b' after 'a' completes");
    }
}
