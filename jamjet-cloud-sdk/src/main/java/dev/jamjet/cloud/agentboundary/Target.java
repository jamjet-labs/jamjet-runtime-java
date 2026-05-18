package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Production system the Action affected. AgentBoundary v0.1 spec §2.5 / §4.7.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Target(
    @JsonProperty("system") String system,
    @JsonProperty("environment") Environment environment,
    @JsonProperty("resource_id") String resourceId
) {
    public Target {
        if (system == null || system.isBlank()) throw new IllegalArgumentException("system is required");
        if (environment == null) throw new IllegalArgumentException("environment is required");
    }
}
