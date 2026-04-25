package dev.jamjet.runtime.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ApproveRequest(
        String decision,
        String nodeId,
        String userId,
        String comment,
        JsonNode statePatch
) {
}
