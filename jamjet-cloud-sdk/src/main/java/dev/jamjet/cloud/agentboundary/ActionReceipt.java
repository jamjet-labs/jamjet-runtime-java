package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AgentBoundary v0.1 Action Receipt — portable, tamper-evident proof of an
 * AI-initiated production action.
 *
 * <p>Implements the v0.1 spec at https://agentboundary.jamjet.dev/schemas/action-receipt-v0.1.json
 *
 * <p>Validate an instance with {@link ActionReceiptValidator}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionReceipt(
    @JsonProperty("version") String version,
    @JsonProperty("receipt_id") String receiptId,
    @JsonProperty("issued_at") String issuedAt,
    @JsonProperty("actor") Actor actor,
    @JsonProperty("agent") Agent agent,
    @JsonProperty("tool") Tool tool,
    @JsonProperty("target") Target target,
    @JsonProperty("arguments_hash") String argumentsHash,
    @JsonProperty("policy") Policy policy,
    @JsonProperty("approval") Approval approval,         // optional, conditional
    @JsonProperty("execution") Execution execution,
    @JsonProperty("receipt_hash") String receiptHash
) {
    public static final String CURRENT_VERSION = "agentboundary/v0.1";

    public ActionReceipt {
        if (!CURRENT_VERSION.equals(version)) {
            throw new IllegalArgumentException("version must be " + CURRENT_VERSION + ", got: " + version);
        }
        if (receiptId == null || receiptId.isBlank()) throw new IllegalArgumentException("receiptId is required");
        if (issuedAt == null || issuedAt.isBlank()) throw new IllegalArgumentException("issuedAt is required");
        if (actor == null) throw new IllegalArgumentException("actor is required");
        if (agent == null) throw new IllegalArgumentException("agent is required");
        if (tool == null) throw new IllegalArgumentException("tool is required");
        if (target == null) throw new IllegalArgumentException("target is required");
        if (argumentsHash == null || !argumentsHash.matches("^[a-f0-9]{64}$"))
            throw new IllegalArgumentException("argumentsHash must be 64 lowercase hex chars");
        if (policy == null) throw new IllegalArgumentException("policy is required");
        if (execution == null) throw new IllegalArgumentException("execution is required");
        if (receiptHash == null || !receiptHash.matches("^[a-f0-9]{64}$"))
            throw new IllegalArgumentException("receiptHash must be 64 lowercase hex chars");

        // Conditional: approval required when policy.decision == require-approval AND execution.status != blocked
        if (policy.decision() == PolicyDecision.REQUIRE_APPROVAL
                && execution.status() != ExecutionStatus.BLOCKED
                && approval == null) {
            throw new IllegalArgumentException(
                "approval block is required when policy.decision is require-approval and the action proceeded");
        }
    }
}
