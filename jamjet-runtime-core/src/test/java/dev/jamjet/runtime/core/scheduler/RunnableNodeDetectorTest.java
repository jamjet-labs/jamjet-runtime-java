package dev.jamjet.runtime.core.scheduler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.QueueType;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.ir.EdgeDef;
import dev.jamjet.runtime.core.ir.NodeDef;
import dev.jamjet.runtime.core.ir.NodeKind;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RunnableNodeDetectorTest {

    private static final ExecutionId EXEC_ID = ExecutionId.create();

    /**
     * Builds a linear workflow: A → B → C with Tool nodes.
     */
    private WorkflowIr linearWorkflow() {
        var nodeA = new NodeDef("a", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);
        var nodeB = new NodeDef("b", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);
        var nodeC = new NodeDef("c", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);

        return new WorkflowIr(
                "test-wf", "1", "Test", null, null, "a",
                Map.of("a", nodeA, "b", nodeB, "c", nodeC),
                List.of(new EdgeDef("a", "b", null), new EdgeDef("b", "c", null)),
                Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                null, null, null, null, null
        );
    }

    private Event event(long seq, EventKind kind) {
        return Event.create(EXEC_ID, seq, kind);
    }

    @Test
    void startNodeRunnableWhenNoEvents() {
        var ir = linearWorkflow();
        var runnable = RunnableNodeDetector.findRunnable(ir, List.of());

        assertTrue(runnable.contains("a"), "Start node 'a' should be runnable");
        assertFalse(runnable.contains("b"), "'b' should not be runnable yet");
        assertFalse(runnable.contains("c"), "'c' should not be runnable yet");
    }

    @Test
    void nextNodeRunnableAfterPredecessorCompletes() {
        var ir = linearWorkflow();
        var events = List.of(
                event(1, new EventKind.NodeScheduled("a", QueueType.TOOL)),
                event(2, new EventKind.NodeStarted("a", "w1", 1)),
                event(3, new EventKind.NodeCompleted("a",
                        JsonNodeFactory.instance.objectNode(), null, 100,
                        null, null, null, null, null, null, null))
        );

        var runnable = RunnableNodeDetector.findRunnable(ir, events);
        assertTrue(runnable.contains("b"), "'b' should be runnable after 'a' completes");
        assertFalse(runnable.contains("a"), "'a' should not be runnable (already completed)");
        assertFalse(runnable.contains("c"), "'c' should not be runnable yet");
    }

    @Test
    void noNodesRunnableWhenPredecessorStillScheduled() {
        var ir = linearWorkflow();
        var events = List.of(
                event(1, new EventKind.NodeScheduled("a", QueueType.TOOL))
        );

        var runnable = RunnableNodeDetector.findRunnable(ir, events);
        assertFalse(runnable.contains("a"), "'a' is already scheduled");
        assertFalse(runnable.contains("b"), "'b' predecessor not complete");
        assertFalse(runnable.contains("c"), "'c' predecessor not complete");
    }

    @Test
    void terminalFailedNodeBlocksSuccessors() {
        var ir = linearWorkflow();
        var events = List.of(
                event(1, new EventKind.NodeScheduled("a", QueueType.TOOL)),
                event(2, new EventKind.NodeStarted("a", "w1", 1)),
                event(3, new EventKind.NodeFailed("a", "boom", 1, false))
        );

        var runnable = RunnableNodeDetector.findRunnable(ir, events);
        assertTrue(runnable.isEmpty(), "No nodes should be runnable when predecessor terminally failed");
    }

    @Test
    void retryableFailedWithRetryScheduledKeepsNodeInScheduled() {
        var ir = linearWorkflow();
        var events = List.of(
                event(1, new EventKind.NodeScheduled("a", QueueType.TOOL)),
                event(2, new EventKind.NodeStarted("a", "w1", 1)),
                event(3, new EventKind.NodeFailed("a", "transient", 1, true)),
                event(4, new EventKind.RetryScheduled("a", 2, 1000))
        );

        var runnable = RunnableNodeDetector.findRunnable(ir, events);
        assertFalse(runnable.contains("a"), "'a' should still be in scheduled (retry)");
        assertFalse(runnable.contains("b"), "'b' predecessor not complete");
    }

    @Test
    void parallelForkBothBranchesRunnableAfterStartCompletes() {
        // start → b, start → c (fork)
        var start = new NodeDef("start", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);
        var b = new NodeDef("b", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);
        var c = new NodeDef("c", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);

        var ir = new WorkflowIr(
                "fork-wf", "1", "Fork", null, null, "start",
                Map.of("start", start, "b", b, "c", c),
                List.of(new EdgeDef("start", "b", null), new EdgeDef("start", "c", null)),
                Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                null, null, null, null, null
        );

        var events = List.of(
                event(1, new EventKind.NodeScheduled("start", QueueType.TOOL)),
                event(2, new EventKind.NodeCompleted("start",
                        JsonNodeFactory.instance.objectNode(), null, 50,
                        null, null, null, null, null, null, null))
        );

        var runnable = RunnableNodeDetector.findRunnable(ir, events);
        assertTrue(runnable.contains("b"), "'b' should be runnable");
        assertTrue(runnable.contains("c"), "'c' should be runnable");
        assertEquals(2, runnable.size());
    }

    @Test
    void joinWaitsForAllPredecessors() {
        // b → join, c → join (both must complete)
        var b = new NodeDef("b", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);
        var c = new NodeDef("c", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);
        var join = new NodeDef("join", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null);

        var ir = new WorkflowIr(
                "join-wf", "1", "Join", null, null, "b",
                Map.of("b", b, "c", c, "join", join),
                List.of(new EdgeDef("b", "join", null), new EdgeDef("c", "join", null)),
                Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                null, null, null, null, null
        );

        // Only 'b' completed — join should not be runnable
        var events1 = new ArrayList<Event>();
        events1.add(event(1, new EventKind.NodeCompleted("b",
                JsonNodeFactory.instance.objectNode(), null, 50,
                null, null, null, null, null, null, null)));

        var runnable1 = RunnableNodeDetector.findRunnable(ir, events1);
        assertFalse(runnable1.contains("join"), "'join' should wait for 'c'");
        assertTrue(runnable1.contains("c"), "'c' has no predecessors so is runnable");

        // Both completed — join should be runnable
        events1.add(event(2, new EventKind.NodeCompleted("c",
                JsonNodeFactory.instance.objectNode(), null, 50,
                null, null, null, null, null, null, null)));

        var runnable2 = RunnableNodeDetector.findRunnable(ir, events1);
        assertTrue(runnable2.contains("join"), "'join' should be runnable now");
    }
}
