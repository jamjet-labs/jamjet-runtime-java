package dev.jamjet.runtime.core;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.Duration;
import java.util.List;

public record RetryPolicy(
        int maxAttempts,
        BackoffStrategy backoff,
        @JsonSerialize(using = DurationSecondsSerializer.class)
        @JsonDeserialize(using = DurationSecondsDeserializer.class)
        Duration initialDelay,
        @JsonSerialize(using = DurationSecondsSerializer.class)
        @JsonDeserialize(using = DurationSecondsDeserializer.class)
        Duration maxDelay,
        boolean jitter,
        List<ErrorClass> retryableOn
) {
    public static RetryPolicy ioDefault() {
        return new RetryPolicy(
                3, BackoffStrategy.EXPONENTIAL,
                Duration.ofSeconds(1), Duration.ofSeconds(30),
                true,
                List.of(ErrorClass.IO_ERROR, ErrorClass.TIMEOUT, ErrorClass.CONNECTION_RESET)
        );
    }

    public static RetryPolicy llmDefault() {
        return new RetryPolicy(
                3, BackoffStrategy.EXPONENTIAL,
                Duration.ofSeconds(2), Duration.ofSeconds(60),
                true,
                List.of(ErrorClass.RATE_LIMIT, ErrorClass.TIMEOUT, ErrorClass.SERVER_ERROR)
        );
    }

    public static RetryPolicy noRetry() {
        return new RetryPolicy(
                1, BackoffStrategy.FIXED,
                Duration.ZERO, Duration.ZERO,
                false, List.of()
        );
    }

    public Duration delayForAttempt(int attempt) {
        if (attempt <= 0) {
            return Duration.ZERO;
        }
        Duration raw = switch (backoff) {
            case FIXED -> initialDelay;
            case LINEAR -> initialDelay.multipliedBy(attempt);
            case EXPONENTIAL -> initialDelay.multipliedBy((long) Math.pow(2, attempt - 1));
        };
        return raw.compareTo(maxDelay) > 0 ? maxDelay : raw;
    }
}
