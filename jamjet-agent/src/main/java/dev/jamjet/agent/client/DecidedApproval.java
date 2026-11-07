package dev.jamjet.agent.client;

/**
 * A decided approval, as served by {@code GET /executions/{id}/approvals}.
 *
 * @param nodeId         the node the decision released or failed
 * @param status         {@code approved} or {@code rejected}
 * @param userId         who decided (the API token's name when the engine runs with auth)
 * @param comment        the rejection comment, if any
 * @param sequence       the event sequence of the decision
 * @param approvedDigest the action digest an approval authorised, {@code null} on a rejection
 */
public record DecidedApproval(
        String nodeId,
        String status,
        String userId,
        String comment,
        Long sequence,
        String approvedDigest
) {
}
