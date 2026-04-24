package dev.jamjet.runtime.core.ir;

public record AgentToolBudget(
        Integer maxTurns,
        Double maxCostUsd,
        Long timeoutMs
) {}
