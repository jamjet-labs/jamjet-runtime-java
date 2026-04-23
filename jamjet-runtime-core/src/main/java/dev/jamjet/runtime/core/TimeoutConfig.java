package dev.jamjet.runtime.core;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.Duration;

public record TimeoutConfig(
        @JsonSerialize(using = DurationSecondsSerializer.class)
        @JsonDeserialize(using = DurationSecondsDeserializer.class)
        Duration nodeTimeout,
        @JsonSerialize(using = DurationSecondsSerializer.class)
        @JsonDeserialize(using = DurationSecondsDeserializer.class)
        Duration workflowTimeout,
        @JsonSerialize(using = DurationSecondsSerializer.class)
        @JsonDeserialize(using = DurationSecondsDeserializer.class)
        Duration heartbeatInterval,
        @JsonSerialize(using = DurationSecondsSerializer.class)
        @JsonDeserialize(using = DurationSecondsDeserializer.class)
        Duration approvalTimeout
) {
    public static TimeoutConfig defaults() {
        return new TimeoutConfig(
                Duration.ofSeconds(300),
                null,
                Duration.ofSeconds(30),
                null
        );
    }
}
