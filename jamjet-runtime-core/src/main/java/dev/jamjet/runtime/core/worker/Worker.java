package dev.jamjet.runtime.core.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.executor.ExecutionResult;
import dev.jamjet.runtime.core.executor.NodeExecutionException;
import dev.jamjet.runtime.core.executor.NodeExecutor;
import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;
import dev.jamjet.runtime.core.ir.NodeDef;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.StateBackendException;
import dev.jamjet.runtime.core.state.WorkItem;
import dev.jamjet.runtime.core.state.WorkflowDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Worker claims work items from the queue, executes them via the appropriate
 * {@link NodeExecutor}, and emits lifecycle events. Designed for virtual threads.
 */
public class Worker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final String workerId;
    private final StateBackend backend;
    private final NodeExecutorRegistry executorRegistry;
    private final List<String> queueTypes;
    private final Duration pollInterval;
    private volatile boolean running = true;

    public Worker(String workerId, StateBackend backend, NodeExecutorRegistry executorRegistry,
                  List<String> queueTypes, Duration pollInterval) {
        this.workerId = workerId;
        this.backend = backend;
        this.executorRegistry = executorRegistry;
        this.queueTypes = List.copyOf(queueTypes);
        this.pollInterval = pollInterval;
    }

    public Worker(String workerId, StateBackend backend, NodeExecutorRegistry executorRegistry,
                  List<String> queueTypes) {
        this(workerId, backend, executorRegistry, queueTypes, Duration.ofMillis(500));
    }

    @Override
    public void run() {
        log.info("Worker {} started, queues={}", workerId, queueTypes);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                boolean claimed = pollAndExecute();
                if (!claimed) {
                    Thread.sleep(pollInterval);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Worker {} poll cycle failed", workerId, e);
                try {
                    Thread.sleep(pollInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Worker {} stopped", workerId);
    }

    /**
     * Attempts to claim and execute one work item.
     *
     * @return true if an item was claimed and processed, false if the queue was empty
     */
    public boolean pollAndExecute() throws StateBackendException {
        Optional<WorkItem> claimed = backend.claimWorkItem(workerId, queueTypes);
        if (claimed.isEmpty()) {
            return false;
        }
        executeItem(claimed.get());
        return true;
    }

    /**
     * Stops the worker loop.
     */
    public void stop() {
        running = false;
    }

    // ── Internal ──────────────────────────────────────────────────────

    private void executeItem(WorkItem item) {
        long startTime = System.currentTimeMillis();

        try {
            // Emit NodeStarted
            long seq = backend.latestSequence(item.executionId()) + 1;
            backend.appendEvent(Event.create(item.executionId(), seq,
                    new EventKind.NodeStarted(item.nodeId(), workerId, item.attempt())));

            // Resolve the node kind tag for executor lookup
            String kindTag = resolveKindTag(item);

            // Look up executor
            Optional<NodeExecutor> executor = executorRegistry.get(kindTag);
            if (executor.isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                long failSeq = backend.latestSequence(item.executionId()) + 1;
                backend.appendEvent(Event.create(item.executionId(), failSeq,
                        new EventKind.NodeFailed(item.nodeId(),
                                "No executor registered for node kind: " + kindTag,
                                item.attempt(), false)));
                backend.failWorkItem(item.id(), "No executor registered for node kind: " + kindTag);
                return;
            }

            // Execute
            try {
                ExecutionResult result = executor.get().execute(item);
                long duration = System.currentTimeMillis() - startTime;

                long completeSeq = backend.latestSequence(item.executionId()) + 1;
                backend.appendEvent(Event.create(item.executionId(), completeSeq,
                        new EventKind.NodeCompleted(
                                item.nodeId(),
                                result.output(),
                                result.statePatch(),
                                result.durationMs() > 0 ? result.durationMs() : duration,
                                result.genAiSystem(),
                                result.genAiModel(),
                                result.inputTokens(),
                                result.outputTokens(),
                                result.finishReason(),
                                result.costUsd(),
                                result.provenance()
                        )));
                backend.completeWorkItem(item.id());

            } catch (NodeExecutionException e) {
                long failSeq = backend.latestSequence(item.executionId()) + 1;
                backend.appendEvent(Event.create(item.executionId(), failSeq,
                        new EventKind.NodeFailed(item.nodeId(), e.getMessage(),
                                item.attempt(), e.isRetryable())));
                backend.failWorkItem(item.id(), e.getMessage());

            } catch (Exception e) {
                long failSeq = backend.latestSequence(item.executionId()) + 1;
                backend.appendEvent(Event.create(item.executionId(), failSeq,
                        new EventKind.NodeFailed(item.nodeId(),
                                "Unexpected error: " + e.getMessage(),
                                item.attempt(), true)));
                backend.failWorkItem(item.id(), "Unexpected error: " + e.getMessage());
            }

        } catch (StateBackendException e) {
            log.error("Worker {} failed to process item {} for node {}",
                    workerId, item.id(), item.nodeId(), e);
        }
    }

    private String resolveKindTag(WorkItem item) {
        try {
            ObjectMapper mapper = JamjetJson.shared();
            JsonNode payload = item.payload();
            String workflowId = payload.path("workflow_id").asText(null);
            String workflowVersion = payload.path("workflow_version").asText(null);

            if (workflowId != null && workflowVersion != null) {
                Optional<WorkflowDefinition> def = backend.getWorkflow(workflowId, workflowVersion);
                if (def.isPresent()) {
                    WorkflowIr ir = mapper.treeToValue(def.get().ir(), WorkflowIr.class);
                    NodeDef nodeDef = ir.node(item.nodeId());
                    if (nodeDef != null) {
                        // Serialize the kind to JSON and extract the "type" field
                        JsonNode kindJson = mapper.valueToTree(nodeDef.kind());
                        String type = kindJson.path("type").asText(null);
                        if (type != null) {
                            return type;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve kind tag for node {}, falling back to queueType", item.nodeId(), e);
        }
        return item.queueType();
    }
}
