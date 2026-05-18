package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Action execution outcome. Matches AgentBoundary v0.1 spec §4.11.
 */
public enum ExecutionStatus {
    @JsonProperty("success") SUCCESS,
    @JsonProperty("failure") FAILURE,
    @JsonProperty("blocked") BLOCKED;
}
