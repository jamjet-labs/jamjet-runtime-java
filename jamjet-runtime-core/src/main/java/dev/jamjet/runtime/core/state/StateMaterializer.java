package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;

import java.util.*;

/**
 * Reconstructs materialized workflow state from the event log.
 * Ports runtime/state/src/materializer.rs from the Rust codebase.
 */
public final class StateMaterializer {

    private StateMaterializer() {}

    /**
     * Materialized view of workflow state, reconstructed from the event log.
     */
    public record MaterializedState(
            JsonNode currentState,
            WorkflowStatus status,
            Map<String, JsonNode> completedNodes,
            Set<String> activeNodes,
            long lastSequence
    ) {}

    /**
     * Materialize the current state of an execution from its event log.
     *
     * @param backend the state backend to read from
     * @param executionId the execution to materialize
     * @return the materialized state
     * @throws StateBackendException if the execution is not found or a backend error occurs
     */
    public static MaterializedState materialize(StateBackend backend, ExecutionId executionId)
            throws StateBackendException {
        // 1. Load execution
        WorkflowExecution execution = backend.getExecution(executionId)
                .orElseThrow(() -> StateBackendException.notFound(executionId.toString()));

        // 2. Load latest snapshot (if any) as base
        JsonNode baseState;
        long baseSequence;
        Optional<Snapshot> snapshot = backend.latestSnapshot(executionId);
        if (snapshot.isPresent()) {
            baseState = snapshot.get().state();
            baseSequence = snapshot.get().atSequence();
        } else {
            baseState = execution.initialInput();
            baseSequence = 0;
        }

        // 3. Load events since base sequence
        List<Event> events = backend.getEventsSince(executionId, baseSequence);

        // 4. Apply events
        return applyEvents(baseState, events);
    }

    /**
     * Apply a list of events to a base state, producing a materialized view.
     *
     * @param baseState the starting state (will be deep-copied, not mutated)
     * @param events    the events to apply in order
     * @return the materialized state after all events
     */
    public static MaterializedState applyEvents(JsonNode baseState, List<Event> events) {
        // Deep copy to avoid mutating the input
        JsonNode currentState = baseState != null ? baseState.deepCopy() : null;
        WorkflowStatus status = WorkflowStatus.PENDING;
        Map<String, JsonNode> completedNodes = new HashMap<>();
        Set<String> activeNodes = new HashSet<>();
        long lastSequence = 0;

        for (Event event : events) {
            lastSequence = event.sequence();

            switch (event.kind()) {
                case EventKind.WorkflowStarted ws -> status = WorkflowStatus.RUNNING;

                case EventKind.WorkflowCompleted wc -> {
                    jsonMergePatch(currentState, wc.finalState());
                    status = WorkflowStatus.COMPLETED;
                }

                case EventKind.WorkflowFailed wf -> status = WorkflowStatus.FAILED;

                case EventKind.WorkflowCancelled wcan -> status = WorkflowStatus.CANCELLED;

                case EventKind.StrategyLimitHit slh -> status = WorkflowStatus.LIMIT_EXCEEDED;

                case EventKind.NodeScheduled ns -> activeNodes.add(ns.nodeId());

                case EventKind.NodeStarted ns -> activeNodes.add(ns.nodeId());

                case EventKind.NodeCompleted nc -> {
                    activeNodes.remove(nc.nodeId());
                    completedNodes.put(nc.nodeId(), nc.output());
                    jsonMergePatch(currentState, nc.statePatch());
                }

                case EventKind.NodeFailed nf -> activeNodes.remove(nf.nodeId());

                case EventKind.NodeSkipped ns -> activeNodes.remove(ns.nodeId());

                case EventKind.NodeCancelled nc -> activeNodes.remove(nc.nodeId());

                case EventKind.InterruptRaised ir -> {
                    if (status == WorkflowStatus.RUNNING) {
                        status = WorkflowStatus.PAUSED;
                    }
                }

                case EventKind.ApprovalReceived ar -> {
                    if (ar.statePatch() != null) {
                        jsonMergePatch(currentState, ar.statePatch());
                    }
                    status = WorkflowStatus.RUNNING;
                }

                default -> {
                    // No-op for all other event kinds
                }
            }
        }

        return new MaterializedState(currentState, status, completedNodes, activeNodes, lastSequence);
    }

    /**
     * RFC 7396 JSON Merge Patch implementation.
     * Mutates the target in place.
     *
     * <ul>
     *   <li>If patch is null or null-node, no-op</li>
     *   <li>If patch is not an object, no-op at top level</li>
     *   <li>If target is not an ObjectNode, no-op</li>
     *   <li>For each field in patch:
     *     <ul>
     *       <li>If value is null → remove key from target</li>
     *       <li>If value is object AND target has object at same key → recurse</li>
     *       <li>Else → set key to deepCopy of value</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    public static void jsonMergePatch(JsonNode target, JsonNode patch) {
        if (patch == null || patch.isNull()) {
            return;
        }
        if (!patch.isObject()) {
            return;
        }
        if (!(target instanceof ObjectNode targetObj)) {
            return;
        }

        var fields = patch.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();

            if (value.isNull()) {
                targetObj.remove(key);
            } else if (value.isObject() && targetObj.has(key) && targetObj.get(key).isObject()) {
                jsonMergePatch(targetObj.get(key), value);
            } else {
                targetObj.set(key, value.deepCopy());
            }
        }
    }
}
