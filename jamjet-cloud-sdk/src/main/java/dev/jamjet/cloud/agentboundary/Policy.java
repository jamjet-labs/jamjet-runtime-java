package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Named, versioned policy that decided. AgentBoundary v0.1 spec §2.6 / §4.9.
 */
public record Policy(
    @JsonProperty("name") String name,
    @JsonProperty("version") String version,
    @JsonProperty("decision") PolicyDecision decision
) {
    public Policy {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version is required");
        if (decision == null) throw new IllegalArgumentException("decision is required");
    }
}
