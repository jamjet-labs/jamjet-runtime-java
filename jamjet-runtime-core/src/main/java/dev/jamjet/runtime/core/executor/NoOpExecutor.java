package dev.jamjet.runtime.core.executor;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.jamjet.runtime.core.state.WorkItem;

/**
 * A no-op executor that returns an empty result immediately.
 * Useful as a default fallback or for testing.
 */
public class NoOpExecutor implements NodeExecutor {

    @Override
    public ExecutionResult execute(WorkItem item) {
        return ExecutionResult.simple(
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                0L
        );
    }
}
