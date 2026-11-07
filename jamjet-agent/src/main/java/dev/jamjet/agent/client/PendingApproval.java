package dev.jamjet.agent.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An approval request still waiting for a decision, as served by
 * {@code GET /executions/{id}/approvals}.
 *
 * @param nodeId    the node the request parks
 * @param toolName  the tool the node would call
 * @param approver  who the request is routed to
 * @param context   what the reviewer is shown; carries {@code action_digest} (or, for an older
 *                  agent tool dispatch, {@code calls_hash})
 * @param sequence  the event sequence of the request
 * @param expiresAt when the request expires (RFC 3339), {@code null} without a deadline
 * @param escalateAt when the request escalates (RFC 3339), {@code null} when it does not
 */
public record PendingApproval(
        String nodeId,
        String toolName,
        String approver,
        JsonNode context,
        long sequence,
        String expiresAt,
        String escalateAt
) {
    /**
     * What this request binds, for a decider to echo back: the {@code action_digest}, or for
     * an older agent tool dispatch the {@code calls_hash}. {@code null} for a request that
     * binds nothing.
     */
    public String binding() {
        if (context == null) {
            return null;
        }
        JsonNode digest = context.get("action_digest");
        if (digest != null && digest.isTextual()) {
            return digest.asText();
        }
        JsonNode calls = context.get("calls_hash");
        return calls != null && calls.isTextual() ? calls.asText() : null;
    }
}
