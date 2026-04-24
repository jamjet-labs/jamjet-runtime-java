package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record WorkflowDefinition(
        String workflowId,
        String version,
        JsonNode ir,
        Instant createdAt,
        String tenantId
) {
    public WorkflowDefinition(String workflowId, String version, JsonNode ir, Instant createdAt) {
        this(workflowId, version, ir, createdAt, "default");
    }
}
