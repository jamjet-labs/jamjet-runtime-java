package dev.jamjet.runtime.core.event;

import dev.jamjet.runtime.core.ExecutionId;

import java.time.Instant;
import java.util.UUID;

public record Event(
        UUID id,
        ExecutionId executionId,
        long sequence,
        EventKind kind,
        Instant createdAt
) {
    public static Event create(ExecutionId executionId, long sequence, EventKind kind) {
        return new Event(UUID.randomUUID(), executionId, sequence, kind, Instant.now());
    }
}
