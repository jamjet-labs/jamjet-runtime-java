package dev.jamjet.runtime.core.state;

public record TenantLimits(
        Integer maxConcurrentExecutions,
        Integer maxWorkflowsPerDay,
        Double maxCostPerDayUsd
) {
}
