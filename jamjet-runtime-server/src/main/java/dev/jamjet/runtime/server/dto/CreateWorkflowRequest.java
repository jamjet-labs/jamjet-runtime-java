package dev.jamjet.runtime.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateWorkflowRequest(JsonNode ir) {
}
