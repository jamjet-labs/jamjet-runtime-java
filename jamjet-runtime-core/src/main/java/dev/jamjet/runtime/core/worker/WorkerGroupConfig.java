package dev.jamjet.runtime.core.worker;

import java.util.List;

/**
 * Configuration for a group of workers that share the same queue types.
 *
 * @param idPrefix    prefix for worker IDs (e.g. "general" → "general-0", "general-1")
 * @param concurrency number of workers to spawn in this group
 * @param queueTypes  queue types these workers will poll
 */
public record WorkerGroupConfig(
        String idPrefix,
        int concurrency,
        List<String> queueTypes
) {
    public WorkerGroupConfig {
        if (idPrefix == null || idPrefix.isBlank()) {
            throw new IllegalArgumentException("idPrefix must not be blank");
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be >= 1, got " + concurrency);
        }
        queueTypes = queueTypes == null ? List.of() : List.copyOf(queueTypes);
    }

    /**
     * Creates a general-purpose worker group that polls all standard queue types.
     *
     * @param concurrency number of workers
     * @return a WorkerGroupConfig with prefix "general" and all queue types
     */
    public static WorkerGroupConfig general(int concurrency) {
        return new WorkerGroupConfig("general", concurrency,
                List.of("model", "tool", "python_tool", "retrieval", "general"));
    }
}
