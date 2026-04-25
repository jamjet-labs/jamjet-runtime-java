package dev.jamjet.runtime.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record StartExecutionRequest(String workflowId, String workflowVersion, JsonNode input) {
}
