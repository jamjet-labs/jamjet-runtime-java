package dev.jamjet.agent.client;

/**
 * Response of {@code POST /executions}. Mirrors the Python client's
 * {@code start_execution} return ({@code {"execution_id": ..., "status": ...}}).
 */
public record StartExecutionResult(String executionId, String status) {}
