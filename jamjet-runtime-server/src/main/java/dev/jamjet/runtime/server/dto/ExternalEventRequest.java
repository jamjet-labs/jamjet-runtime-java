package dev.jamjet.runtime.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ExternalEventRequest(String correlationKey, JsonNode payload) {
}
