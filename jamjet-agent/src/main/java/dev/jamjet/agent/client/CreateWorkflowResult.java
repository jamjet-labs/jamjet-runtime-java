package dev.jamjet.agent.client;

/**
 * Response of {@code POST /workflows}. Mirrors the Python client's
 * {@code create_workflow} return ({@code {"workflow_id": ...}}); {@code version}
 * is populated when the engine echoes it.
 */
public record CreateWorkflowResult(String workflowId, String version) {}
