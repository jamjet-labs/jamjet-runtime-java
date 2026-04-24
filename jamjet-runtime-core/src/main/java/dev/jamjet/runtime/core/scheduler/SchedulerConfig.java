package dev.jamjet.runtime.core.scheduler;

import java.time.Duration;

/**
 * Configuration for the {@link Scheduler}.
 *
 * @param pollInterval                how often the scheduler scans for runnable nodes
 * @param maxConcurrentNodesPerExecution max active (scheduled/started) nodes per execution
 * @param maxDispatchPerTick          max new work items dispatched in a single tick
 */
public record SchedulerConfig(
        Duration pollInterval,
        int maxConcurrentNodesPerExecution,
        int maxDispatchPerTick
) {

    /**
     * Returns sensible defaults: 500ms poll, 16 concurrent nodes, 8 dispatches per tick.
     */
    public static SchedulerConfig defaults() {
        return new SchedulerConfig(Duration.ofMillis(500), 16, 8);
    }
}
