package dev.jamjet.runtime.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record UpdateTenantRequest(String name, String status, JsonNode policy, JsonNode limits) {
}
