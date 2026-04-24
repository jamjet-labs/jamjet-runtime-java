package dev.jamjet.runtime.core.state;

import java.util.List;

public record ReclaimResult(
        List<WorkItem> retryable,
        List<WorkItem> exhausted
) {
    public ReclaimResult {
        retryable = retryable == null ? List.of() : List.copyOf(retryable);
        exhausted = exhausted == null ? List.of() : List.copyOf(exhausted);
    }

    public static ReclaimResult empty() {
        return new ReclaimResult(List.of(), List.of());
    }
}
