package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Named capability invoked. AgentBoundary v0.1 spec §2.4 / §4.6.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tool(
    @JsonProperty("name") String name,
    @JsonProperty("version") String version,
    @JsonProperty("capability") String capability
) {
    public Tool {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (capability == null || capability.isBlank()) throw new IllegalArgumentException("capability is required");
    }
}
