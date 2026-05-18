package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Action outcome. AgentBoundary v0.1 spec §4.11.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Execution(
    @JsonProperty("status") ExecutionStatus status,
    @JsonProperty("completed_at") String completedAt,  // RFC 3339 string
    @JsonProperty("error_code") String errorCode,
    @JsonProperty("result_ref") String resultRef
) {
    public Execution {
        if (status == null) throw new IllegalArgumentException("status is required");
        if (completedAt == null || completedAt.isBlank()) throw new IllegalArgumentException("completedAt is required");
    }
}
