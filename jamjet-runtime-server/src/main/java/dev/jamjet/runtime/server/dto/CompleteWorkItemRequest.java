package dev.jamjet.runtime.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record CompleteWorkItemRequest(
        String executionId,
        String nodeId,
        JsonNode output,
        JsonNode statePatch,
        Long durationMs
) {
}
