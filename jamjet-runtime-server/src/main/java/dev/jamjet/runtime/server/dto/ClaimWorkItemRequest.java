package dev.jamjet.runtime.server.dto;

import java.util.List;

public record ClaimWorkItemRequest(String workerId, List<String> queueTypes) {
}
