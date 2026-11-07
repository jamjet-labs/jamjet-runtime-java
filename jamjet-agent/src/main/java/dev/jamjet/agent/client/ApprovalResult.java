package dev.jamjet.agent.client;

/** The engine's answer to a recorded decision: {@code {"execution_id", "node_id", "accepted"}}. */
public record ApprovalResult(String executionId, String nodeId, boolean accepted) {
}
