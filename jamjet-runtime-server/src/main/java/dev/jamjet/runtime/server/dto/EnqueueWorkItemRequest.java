package dev.jamjet.runtime.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record EnqueueWorkItemRequest(
        String executionId,
        String nodeId,
        String queueType,
        JsonNode payload
) {
}
