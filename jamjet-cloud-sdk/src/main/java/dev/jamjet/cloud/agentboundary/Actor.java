package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Who initiated the Action. AgentBoundary v0.1 spec §2.2 / §4.4.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Actor(
    @JsonProperty("type") ActorType type,
    @JsonProperty("id") String id,
    @JsonProperty("display_name") String displayName
) {
    public Actor {
        if (type == null) throw new IllegalArgumentException("type is required");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
    }
}
