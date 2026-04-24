package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.ExecutionId;

import java.time.Instant;
import java.util.UUID;

public record Snapshot(
        UUID id,
        ExecutionId executionId,
        long atSequence,
        JsonNode state,
        Instant createdAt
) {
    public static final int DEFAULT_SNAPSHOT_INTERVAL = 50;

    public static Snapshot create(ExecutionId executionId, long atSequence, JsonNode state) {
        return new Snapshot(UUID.randomUUID(), executionId, atSequence, state, Instant.now());
    }

    public static boolean shouldSnapshot(long eventsSinceSnapshot) {
        return eventsSinceSnapshot >= DEFAULT_SNAPSHOT_INTERVAL;
    }
}
