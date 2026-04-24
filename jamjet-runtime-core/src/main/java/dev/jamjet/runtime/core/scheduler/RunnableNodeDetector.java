package dev.jamjet.runtime.core.scheduler;

import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.ir.EdgeDef;
import dev.jamjet.runtime.core.ir.WorkflowIr;

import java.util.*;

/**
 * Pure function that determines which workflow nodes are ready to execute
 * based on the current event history.
 */
public final class RunnableNodeDetector {

    private RunnableNodeDetector() {}

    /**
     * Finds all nodes that are ready to run: not yet scheduled/completed/terminally-failed,
     * and all predecessor nodes (edges pointing TO the node) are completed.
     *
     * @param ir     the workflow intermediate representation
     * @param events the event history for this execution
     * @return deterministically-ordered set of runnable node IDs
     */
    public static Set<String> findRunnable(WorkflowIr ir, List<Event> events) {
        var completed = new HashSet<String>();
        var scheduled = new HashSet<String>();
        var terminalFailed = new HashSet<String>();

        for (Event event : events) {
            switch (event.kind()) {
                case EventKind.NodeCompleted nc -> {
                    completed.add(nc.nodeId());
                    scheduled.remove(nc.nodeId());
                }
                case EventKind.NodeSkipped ns -> {
                    completed.add(ns.nodeId());
                    scheduled.remove(ns.nodeId());
                }
                case EventKind.NodeCancelled nc -> {
                    completed.add(nc.nodeId());
                    scheduled.remove(nc.nodeId());
                }
                case EventKind.NodeScheduled ns -> scheduled.add(ns.nodeId());
                case EventKind.NodeStarted ns -> scheduled.add(ns.nodeId());
                case EventKind.NodeFailed nf -> {
                    if (!nf.retryable()) {
                        terminalFailed.add(nf.nodeId());
                        scheduled.remove(nf.nodeId());
                    } else {
                        scheduled.remove(nf.nodeId());
                    }
                }
                case EventKind.RetryScheduled rs -> scheduled.add(rs.nodeId());
                default -> { /* ignore all other event kinds */ }
            }
        }

        // Build predecessor map: for each node, which nodes must be completed first
        var predecessors = new HashMap<String, Set<String>>();
        for (var node : ir.nodes().keySet()) {
            predecessors.put(node, new HashSet<>());
        }
        for (EdgeDef edge : ir.edges()) {
            predecessors.computeIfAbsent(edge.to(), k -> new HashSet<>()).add(edge.from());
        }

        var runnable = new LinkedHashSet<String>();
        for (var nodeId : ir.nodes().keySet()) {
            if (scheduled.contains(nodeId) || completed.contains(nodeId) || terminalFailed.contains(nodeId)) {
                continue;
            }
            var preds = predecessors.getOrDefault(nodeId, Set.of());
            if (completed.containsAll(preds)) {
                runnable.add(nodeId);
            }
        }

        return runnable;
    }
}
