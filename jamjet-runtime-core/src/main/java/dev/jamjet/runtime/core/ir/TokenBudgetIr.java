package dev.jamjet.runtime.core.ir;

public record TokenBudgetIr(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {}
