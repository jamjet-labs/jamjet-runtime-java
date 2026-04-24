package dev.jamjet.runtime.core.state;

import java.time.Instant;

public record ApiToken(
        String id,
        String name,
        String role,
        Instant createdAt,
        Instant expiresAt,
        String tenantId
) {
    public ApiToken(String id, String name, String role, Instant createdAt, Instant expiresAt) {
        this(id, name, role, createdAt, expiresAt, "default");
    }

    public ApiToken(String id, String name, String role, Instant createdAt) {
        this(id, name, role, createdAt, null, "default");
    }
}
