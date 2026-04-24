package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.SessionType;
import dev.jamjet.runtime.core.WorkflowStatus;

import java.time.Instant;

public record WorkflowExecution(
        ExecutionId executionId,
        String workflowId,
        String workflowVersion,
        WorkflowStatus status,
        JsonNode initialInput,
        JsonNode currentState,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        SessionType sessionType
) {
}
