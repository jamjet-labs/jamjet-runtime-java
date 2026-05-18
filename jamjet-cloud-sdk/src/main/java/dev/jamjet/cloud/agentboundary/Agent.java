package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI software stack that produced the Action. AgentBoundary v0.1 spec §2.3 / §4.5.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Agent(
    @JsonProperty("framework") String framework,
    @JsonProperty("framework_version") String frameworkVersion,
    @JsonProperty("model") String model,
    @JsonProperty("model_version") String modelVersion
) {
    public Agent {
        if (framework == null || framework.isBlank()) throw new IllegalArgumentException("framework is required");
        if (frameworkVersion == null || frameworkVersion.isBlank()) throw new IllegalArgumentException("frameworkVersion is required");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
    }
}
