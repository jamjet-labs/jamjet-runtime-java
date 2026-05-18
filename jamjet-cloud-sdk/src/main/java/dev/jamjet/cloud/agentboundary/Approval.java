package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Approval block when policy requires approval. AgentBoundary v0.1 spec §2.7 / §4.10.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Approval(
    @JsonProperty("approver") Approver approver,
    @JsonProperty("approved_at") String approvedAt,  // RFC 3339 string
    @JsonProperty("context") String context
) {
    public Approval {
        if (approver == null) throw new IllegalArgumentException("approver is required");
        if (approvedAt == null || approvedAt.isBlank()) throw new IllegalArgumentException("approvedAt is required");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Approver(
        @JsonProperty("id") String id,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("role") String role
    ) {
        public Approver {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        }
    }
}
