package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.ExecutionId;

import java.time.Instant;
import java.util.UUID;

public record WorkItem(
        UUID id,
        ExecutionId executionId,
        String nodeId,
        String queueType,
        JsonNode payload,
        int attempt,
        int maxAttempts,
        Instant createdAt,
        Instant leaseExpiresAt,
        String workerId,
        String tenantId
) {
    public WorkItem(UUID id, ExecutionId executionId, String nodeId, String queueType,
                    JsonNode payload, int attempt, int maxAttempts, Instant createdAt,
                    Instant leaseExpiresAt, String workerId) {
        this(id, executionId, nodeId, queueType, payload, attempt, maxAttempts,
                createdAt, leaseExpiresAt, workerId, "default");
    }
}
