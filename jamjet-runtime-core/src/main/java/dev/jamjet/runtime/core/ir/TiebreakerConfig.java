package dev.jamjet.runtime.core.ir;

public record TiebreakerConfig(
        String model,
        String prompt,
        Integer maxTokens
) {}
