package dev.jamjet.runtime.core.executor;

import dev.jamjet.runtime.core.state.WorkItem;

/**
 * SPI for pluggable node executors.
 *
 * <p>Implementations must be thread-safe — multiple workers may invoke
 * {@link #execute(WorkItem)} concurrently on the same instance.</p>
 */
public interface NodeExecutor {

    /**
     * Executes the work described by the given item.
     *
     * @param item the work item to execute
     * @return the execution result
     * @throws NodeExecutionException if execution fails
     */
    ExecutionResult execute(WorkItem item) throws NodeExecutionException;
}
