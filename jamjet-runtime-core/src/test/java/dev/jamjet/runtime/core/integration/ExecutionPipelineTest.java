package dev.jamjet.runtime.core.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.SessionType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.executor.ExecutionResult;
import dev.jamjet.runtime.core.executor.NodeExecutionException;
import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;
import dev.jamjet.runtime.core.ir.EdgeDef;
import dev.jamjet.runtime.core.ir.NodeDef;
import dev.jamjet.runtime.core.ir.NodeKind;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import dev.jamjet.runtime.core.scheduler.Scheduler;
import dev.jamjet.runtime.core.scheduler.SchedulerConfig;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import dev.jamjet.runtime.core.state.StateMaterializer;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.WorkflowDefinition;
import dev.jamjet.runtime.core.state.WorkflowExecution;
import dev.jamjet.runtime.core.worker.Worker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests verifying the complete execution pipeline:
 * register workflow -> start execution -> scheduler dispatches -> worker executes -> verify state.
 */
class ExecutionPipelineTest {

    private static final ObjectMapper MAPPER = JamjetJson.shared();

    private StateBackend backend;
    private Scheduler scheduler;
    private NodeExecutorRegistry registry;

    @BeforeEach
    void setUp() {
        backend = new InMemoryStateBackend();
        scheduler = new Scheduler(backend, SchedulerConfig.defaults());
        registry = new NodeExecutorRegistry();
    }

    @Test
    void linearWorkflowExecutesToCompletion() throws Exception {
        // Register a "tool" executor that produces output and state patch keyed by nodeId
        registry.register("tool", item -> {
            String nodeId = item.nodeId();
            ObjectNode output = MAPPER.createObjectNode().put("result", "done_" + nodeId);
            ObjectNode patch = MAPPER.createObjectNode().put(nodeId + "_done", true);
            return ExecutionResult.simple(output, patch, 10);
        });

        // Build A -> B workflow (both Tool nodes)
        WorkflowIr ir = new WorkflowIr(
                "linear-wf", "1", "Linear Workflow", null, null, "a",
                Map.of(
                        "a", new NodeDef("a", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null),
                        "b", new NodeDef("b", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null)
                ),
                List.of(new EdgeDef("a", "b", null)),
                null, null, null, null, null, null, null, null, null, null, null, null
        );

        // Store workflow definition
        JsonNode irJson = MAPPER.valueToTree(ir);
        backend.storeWorkflow(new WorkflowDefinition("linear-wf", "1", irJson, Instant.now()));

        // Create execution in RUNNING state with WorkflowStarted event
        ExecutionId execId = ExecutionId.create();
        ObjectNode initialInput = MAPPER.createObjectNode();
        backend.createExecution(new WorkflowExecution(
                execId, "linear-wf", "1", WorkflowStatus.RUNNING,
                initialInput, initialInput, Instant.now(), Instant.now(), null,
                SessionType.STATELESS
        ));
        backend.appendEvent(Event.create(execId, 1,
                new EventKind.WorkflowStarted("linear-wf", "1", initialInput)));

        // Create a worker
        Worker worker = new Worker("test-worker", backend, registry,
                List.of("model", "tool", "python_tool", "retrieval", "general"));

        // Tick 1: scheduler dispatches node "a", worker executes it
        scheduler.tick();
        worker.pollAndExecute();

        // Tick 2: scheduler dispatches node "b", worker executes it
        scheduler.tick();
        worker.pollAndExecute();

        // Verify events
        List<Event> events = backend.getEvents(execId);
        List<String> eventTypes = events.stream()
                .map(e -> e.kind().getClass().getSimpleName())
                .toList();

        assertEquals(List.of(
                "WorkflowStarted",
                "NodeScheduled", "NodeStarted", "NodeCompleted",
                "NodeScheduled", "NodeStarted", "NodeCompleted"
        ), eventTypes, "Expected exactly 7 events in order");

        // Verify materialized state
        StateMaterializer.MaterializedState state = StateMaterializer.materialize(backend, execId);
        assertTrue(state.currentState().path("a_done").asBoolean(false), "a_done should be true");
        assertTrue(state.currentState().path("b_done").asBoolean(false), "b_done should be true");
        assertTrue(state.completedNodes().containsKey("a"), "Node 'a' should be completed");
        assertTrue(state.completedNodes().containsKey("b"), "Node 'b' should be completed");
        assertTrue(state.activeNodes().isEmpty(), "No active nodes should remain");
    }

    @Test
    void parallelForkExecutesBothBranches() throws Exception {
        // Register "tool" executor returning node-identifying output, empty patch
        registry.register("tool", item -> {
            ObjectNode output = MAPPER.createObjectNode().put("node", item.nodeId());
            ObjectNode patch = MAPPER.createObjectNode();
            return ExecutionResult.simple(output, patch, 5);
        });

        // Build start -> left, start -> right workflow (3 nodes, 2 edges)
        WorkflowIr ir = new WorkflowIr(
                "fork-wf", "1", "Fork Workflow", null, null, "start",
                Map.of(
                        "start", new NodeDef("start", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null),
                        "left", new NodeDef("left", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null),
                        "right", new NodeDef("right", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null)
                ),
                List.of(
                        new EdgeDef("start", "left", null),
                        new EdgeDef("start", "right", null)
                ),
                null, null, null, null, null, null, null, null, null, null, null, null
        );

        JsonNode irJson = MAPPER.valueToTree(ir);
        backend.storeWorkflow(new WorkflowDefinition("fork-wf", "1", irJson, Instant.now()));

        ExecutionId execId = ExecutionId.create();
        ObjectNode initialInput = MAPPER.createObjectNode();
        backend.createExecution(new WorkflowExecution(
                execId, "fork-wf", "1", WorkflowStatus.RUNNING,
                initialInput, initialInput, Instant.now(), Instant.now(), null,
                SessionType.STATELESS
        ));
        backend.appendEvent(Event.create(execId, 1,
                new EventKind.WorkflowStarted("fork-wf", "1", initialInput)));

        Worker worker = new Worker("test-worker", backend, registry,
                List.of("model", "tool", "python_tool", "retrieval", "general"));

        // Tick 1: scheduler dispatches "start", worker executes
        scheduler.tick();
        worker.pollAndExecute();

        // Tick 2: scheduler dispatches "left" and "right", worker executes both
        scheduler.tick();
        worker.pollAndExecute();
        worker.pollAndExecute();

        // Verify all three nodes completed
        StateMaterializer.MaterializedState state = StateMaterializer.materialize(backend, execId);
        assertTrue(state.completedNodes().containsKey("start"), "Node 'start' should be completed");
        assertTrue(state.completedNodes().containsKey("left"), "Node 'left' should be completed");
        assertTrue(state.completedNodes().containsKey("right"), "Node 'right' should be completed");
        assertTrue(state.activeNodes().isEmpty(), "No active nodes should remain");
    }

    @Test
    void failedNodeEmitsCorrectEvents() throws Exception {
        // Register "tool" executor that always fails
        registry.register("tool", item -> {
            throw new NodeExecutionException("database connection failed", true);
        });

        // Single-node workflow
        WorkflowIr ir = new WorkflowIr(
                "fail-wf", "1", "Fail Workflow", null, null, "a",
                Map.of(
                        "a", new NodeDef("a", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null)
                ),
                List.of(),
                null, null, null, null, null, null, null, null, null, null, null, null
        );

        JsonNode irJson = MAPPER.valueToTree(ir);
        backend.storeWorkflow(new WorkflowDefinition("fail-wf", "1", irJson, Instant.now()));

        ExecutionId execId = ExecutionId.create();
        ObjectNode initialInput = MAPPER.createObjectNode();
        backend.createExecution(new WorkflowExecution(
                execId, "fail-wf", "1", WorkflowStatus.RUNNING,
                initialInput, initialInput, Instant.now(), Instant.now(), null,
                SessionType.STATELESS
        ));
        backend.appendEvent(Event.create(execId, 1,
                new EventKind.WorkflowStarted("fail-wf", "1", initialInput)));

        Worker worker = new Worker("test-worker", backend, registry,
                List.of("model", "tool", "python_tool", "retrieval", "general"));

        // Scheduler dispatches, worker executes (and fails)
        scheduler.tick();
        worker.pollAndExecute();

        // Verify events contain NodeFailed
        List<Event> events = backend.getEvents(execId);
        List<EventKind> kinds = events.stream().map(Event::kind).toList();

        // Find the NodeFailed event
        EventKind.NodeFailed nodeFailed = kinds.stream()
                .filter(k -> k instanceof EventKind.NodeFailed)
                .map(k -> (EventKind.NodeFailed) k)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a NodeFailed event"));

        assertTrue(nodeFailed.error().contains("database connection failed"),
                "Error message should contain 'database connection failed'");
        assertTrue(nodeFailed.retryable(), "NodeFailed should be retryable");
        assertEquals("a", nodeFailed.nodeId(), "Failed node should be 'a'");
    }
}
