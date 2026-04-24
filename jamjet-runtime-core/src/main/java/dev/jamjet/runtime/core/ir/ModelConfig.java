package dev.jamjet.runtime.core.ir;

public record ModelConfig(
        String provider,
        String model,
        Long timeoutSecs,
        String retryPolicy,
        Float temperature,
        Integer maxTokens
) {}
