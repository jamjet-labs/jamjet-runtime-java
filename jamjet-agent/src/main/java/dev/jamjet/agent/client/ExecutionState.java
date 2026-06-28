package dev.jamjet.agent.client;

import java.util.Map;

/**
 * Response of {@code GET /executions/{id}} — the polled execution snapshot.
 * Mirrors the Python client's {@code get_execution} shape ({@code {status,
 * current_state, ...}}). The engine serializes status in snake_case, so a
 * terminal status is {@code "completed"} (or {@code "failed"} /
 * {@code "cancelled"} / {@code "limit_exceeded"}); {@code currentState} carries
 * the accumulated execution state.
 */
public record ExecutionState(
        String executionId,
        String status,
        Map<String, Object> currentState
) {}
