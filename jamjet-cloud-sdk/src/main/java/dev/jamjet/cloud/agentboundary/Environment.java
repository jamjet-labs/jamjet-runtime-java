package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Target deployment environment. Matches AgentBoundary v0.1 spec §4.7.
 */
public enum Environment {
    @JsonProperty("prod")    PROD,
    @JsonProperty("staging") STAGING,
    @JsonProperty("dev")     DEV;
}
