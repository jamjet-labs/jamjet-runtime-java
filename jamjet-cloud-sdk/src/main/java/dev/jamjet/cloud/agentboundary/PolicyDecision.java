package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Policy outcome. Matches AgentBoundary v0.1 spec §2.6.
 */
public enum PolicyDecision {
    @JsonProperty("allow")            ALLOW,
    @JsonProperty("deny")             DENY,
    @JsonProperty("escalate")         ESCALATE,
    @JsonProperty("require-approval") REQUIRE_APPROVAL;
}
