package dev.jamjet.agent.client;

import java.util.Map;

/**
 * Response of {@code GET /executions/{id}} — the polled execution snapshot.
 * Mirrors the Python client's {@code get_execution} shape ({@code {status,
 * current_state, ...}}). The terminal status is {@code "Completed"} (or
 * {@code "Failed"} / {@code "Cancelled"}); {@code currentState} carries the
 * accumulated execution state.
 */
public record ExecutionState(
        String executionId,
        String status,
        Map<String, Object> currentState
) {}
