package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Who initiated the Action. Matches AgentBoundary v0.1 spec §2.2.
 */
public enum ActorType {
    @JsonProperty("human")  HUMAN,
    @JsonProperty("system") SYSTEM,
    @JsonProperty("agent")  AGENT;
}
