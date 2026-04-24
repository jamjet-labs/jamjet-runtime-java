package dev.jamjet.runtime.core.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.executor.NodeExecutionException;
import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;
import dev.jamjet.runtime.core.executor.NoOpExecutor;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkerTest {

    private InMemoryStateBackend backend;
    private NodeExecutorRegistry registry;
    private Worker worker;
    private final ObjectMapper mapper = JamjetJson.shared();

    @BeforeEach
    void setUp() {
        backend = new InMemoryStateBackend();
        registry = new NodeExecutorRegistry();
        registry.register("tool", new NoOpExecutor());
        worker = new Worker("test-worker", backend, registry, List.of("tool"));
    }

    private ExecutionId setupWorkflowAndItem() throws Exception {
        // Single-node workflow with node "a" of kind Tool
        var nodeA = new NodeDef("a", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);
        var ir = new WorkflowIr(
                "w1", "1", "Test", null, null, "a",
                Map.of("a", nodeA),
                List.of(),
                Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                null, null, null, null, null
        );

        // Store workflow definition
        JsonNode irJson = mapper.valueToTree(ir);
        backend.storeWorkflow(new WorkflowDefinition("w1", "1", irJson, Instant.now()));

        // Create RUNNING execution
        ExecutionId execId = ExecutionId.create();
        backend.createExecution(new WorkflowExecution(
                execId, "w1", "1", WorkflowStatus.RUNNING,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                Instant.now(), Instant.now(), null, null
        ));

        // Enqueue work item
        ObjectNode payload = mapper.createObjectNode();
        payload.put("workflow_id", "w1");
        payload.put("workflow_version", "1");
        payload.put("node_id", "a");

        WorkItem item = new WorkItem(
                UUID.randomUUID(), execId, "a", "tool",
                payload, 1, 3, Instant.now(), null, null
        );
        backend.enqueueWorkItem(item);

        return execId;
    }

    @Test
    void pollAndExecuteClaimsAndCompletesItem() throws Exception {
        ExecutionId execId = setupWorkflowAndItem();

        boolean result = worker.pollAndExecute();
        assertTrue(result, "Should claim and execute an item");

        var events = backend.getEvents(execId);
        boolean hasStarted = events.stream()
                .anyMatch(e -> e.kind() instanceof EventKind.NodeStarted ns
                        && ns.nodeId().equals("a")
                        && ns.workerId().equals("test-worker"));
        assertTrue(hasStarted, "Should emit NodeStarted");

        boolean hasCompleted = events.stream()
                .anyMatch(e -> e.kind() instanceof EventKind.NodeCompleted nc
                        && nc.nodeId().equals("a"));
        assertTrue(hasCompleted, "Should emit NodeCompleted");
    }

    @Test
    void pollReturnsFalseWhenNoItems() throws Exception {
        boolean result = worker.pollAndExecute();
        assertFalse(result, "Should return false when no items available");
    }

    @Test
    void pollEmitsNodeFailedOnExecutorError() throws Exception {
        // Replace registry with a failing executor
        registry = new NodeExecutorRegistry();
        registry.register("tool", item -> {
            throw new NodeExecutionException("transient failure", true);
        });
        worker = new Worker("test-worker", backend, registry, List.of("tool"));

        ExecutionId execId = setupWorkflowAndItem();

        boolean result = worker.pollAndExecute();
        assertTrue(result, "Should claim an item");

        var events = backend.getEvents(execId);
        boolean hasFailed = events.stream()
                .anyMatch(e -> e.kind() instanceof EventKind.NodeFailed nf
                        && nf.nodeId().equals("a")
                        && nf.retryable()
                        && nf.error().contains("transient failure"));
        assertTrue(hasFailed, "Should emit NodeFailed with retryable=true");
    }

    @Test
    void pollHandlesMissingExecutor() throws Exception {
        // Use empty registry
        registry = new NodeExecutorRegistry();
        worker = new Worker("test-worker", backend, registry, List.of("tool"));

        ExecutionId execId = setupWorkflowAndItem();

        boolean result = worker.pollAndExecute();
        assertTrue(result, "Should claim an item");

        var events = backend.getEvents(execId);
        boolean hasFailed = events.stream()
                .anyMatch(e -> e.kind() instanceof EventKind.NodeFailed nf
                        && nf.nodeId().equals("a")
                        && !nf.retryable()
                        && nf.error().contains("No executor"));
        assertTrue(hasFailed, "Should emit NodeFailed with 'No executor' message and retryable=false");
    }
}
