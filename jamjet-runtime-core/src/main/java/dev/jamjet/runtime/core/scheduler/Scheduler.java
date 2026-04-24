package dev.jamjet.runtime.core.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.QueueType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import dev.jamjet.runtime.core.state.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Scheduler detects runnable nodes across active workflow executions and
 * dispatches work items for them. Designed to run on a virtual thread.
 */
public class Scheduler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final StateBackend backend;
    private final SchedulerConfig config;
    private volatile boolean running = true;

    public Scheduler(StateBackend backend, SchedulerConfig config) {
        this.backend = backend;
        this.config = config;
    }

    public Scheduler(StateBackend backend) {
        this(backend, SchedulerConfig.defaults());
    }

    @Override
    public void run() {
        log.info("Scheduler started with poll interval {}ms", config.pollInterval().toMillis());
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                tick();
                Thread.sleep(config.pollInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Scheduler tick failed", e);
            }
        }
        log.info("Scheduler stopped");
    }

    /**
     * Performs one scheduler cycle: reclaims expired leases, then scans
     * all running executions for runnable nodes.
     */
    public void tick() throws StateBackendException {
        reclaimLeases();

        List<WorkflowExecution> executions = backend.listExecutions(WorkflowStatus.RUNNING, 100, 0);
        for (WorkflowExecution execution : executions) {
            try {
                scheduleRunnableNodes(execution);
            } catch (Exception e) {
                log.warn("Failed to schedule nodes for execution {}", execution.executionId(), e);
            }
        }
    }

    /**
     * Stops the scheduler loop.
     */
    public void stop() {
        running = false;
    }

    // ── Internal ──────────────────────────────────────────────────────

    private void reclaimLeases() throws StateBackendException {
        ReclaimResult result = backend.reclaimExpiredLeases();

        for (WorkItem item : result.retryable()) {
            long seq = backend.latestSequence(item.executionId()) + 1;
            backend.appendEvent(Event.create(item.executionId(), seq,
                    new EventKind.NodeFailed(item.nodeId(), "Lease expired", item.attempt(), true)));
            backend.appendEvent(Event.create(item.executionId(), seq + 1,
                    new EventKind.RetryScheduled(item.nodeId(), item.attempt() + 1, 0)));
        }

        for (WorkItem item : result.exhausted()) {
            long seq = backend.latestSequence(item.executionId()) + 1;
            backend.appendEvent(Event.create(item.executionId(), seq,
                    new EventKind.NodeFailed(item.nodeId(), "Lease expired — max attempts exhausted",
                            item.attempt(), false)));
        }
    }

    private void scheduleRunnableNodes(WorkflowExecution execution) throws Exception {
        ObjectMapper mapper = JamjetJson.shared();
        WorkflowDefinition def = backend.getWorkflow(execution.workflowId(), execution.workflowVersion())
                .orElse(null);
        if (def == null) {
            log.warn("Workflow definition not found for {}/{}", execution.workflowId(), execution.workflowVersion());
            return;
        }

        WorkflowIr ir = mapper.treeToValue(def.ir(), WorkflowIr.class);
        List<Event> events = backend.getEvents(execution.executionId());
        var runnable = RunnableNodeDetector.findRunnable(ir, events);

        if (runnable.isEmpty()) {
            return;
        }

        // Count active nodes (scheduled or started but not completed/failed)
        long activeCount = countActiveNodes(events);
        if (activeCount >= config.maxConcurrentNodesPerExecution()) {
            return;
        }

        int dispatched = 0;
        for (String nodeId : runnable) {
            if (dispatched >= config.maxDispatchPerTick()) break;
            if (activeCount + dispatched >= config.maxConcurrentNodesPerExecution()) break;

            var nodeDef = ir.node(nodeId);
            if (nodeDef == null) continue;

            QueueType queueType = nodeDef.kind().queueType();

            // Emit NodeScheduled event
            long seq = backend.latestSequence(execution.executionId()) + 1;
            backend.appendEvent(Event.create(execution.executionId(), seq,
                    new EventKind.NodeScheduled(nodeId, queueType)));

            // Determine max attempts from retry policy
            int maxAttempts = resolveMaxAttempts(nodeDef.retryPolicy());

            // Build work item payload
            ObjectNode payload = mapper.createObjectNode();
            payload.put("workflow_id", execution.workflowId());
            payload.put("workflow_version", execution.workflowVersion());
            payload.put("node_id", nodeId);

            WorkItem item = new WorkItem(
                    UUID.randomUUID(),
                    execution.executionId(),
                    nodeId,
                    queueType.getValue(),
                    payload,
                    1,           // first attempt
                    maxAttempts,
                    Instant.now(),
                    null,        // no lease yet
                    null         // no worker yet
            );
            backend.enqueueWorkItem(item);
            dispatched++;
        }
    }

    private long countActiveNodes(List<Event> events) {
        var active = new java.util.HashSet<String>();
        for (Event event : events) {
            switch (event.kind()) {
                case EventKind.NodeScheduled ns -> active.add(ns.nodeId());
                case EventKind.NodeStarted ns -> active.add(ns.nodeId());
                case EventKind.NodeCompleted nc -> active.remove(nc.nodeId());
                case EventKind.NodeFailed nf -> {
                    if (!nf.retryable()) active.remove(nf.nodeId());
                }
                case EventKind.NodeSkipped ns -> active.remove(ns.nodeId());
                case EventKind.NodeCancelled nc -> active.remove(nc.nodeId());
                default -> {}
            }
        }
        return active.size();
    }

    static int resolveMaxAttempts(String retryPolicy) {
        if (retryPolicy == null) return 3;
        return switch (retryPolicy) {
            case "no_retry" -> 1;
            case "io_default" -> 5;
            case "llm_default" -> 3;
            default -> 3;
        };
    }
}
