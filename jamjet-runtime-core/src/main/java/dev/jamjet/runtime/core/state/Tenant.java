package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record Tenant(
        String id,
        String name,
        TenantStatus status,
        JsonNode policy,
        TenantLimits limits,
        Instant createdAt,
        Instant updatedAt
) {
}
