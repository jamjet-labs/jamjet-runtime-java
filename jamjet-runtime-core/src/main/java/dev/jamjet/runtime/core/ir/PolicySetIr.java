package dev.jamjet.runtime.core.ir;

import java.util.List;

/**
 * A policy level (tenant, workflow or node), mirroring {@code PolicySetIr} in
 * {@code runtime/ir/src/workflow.rs}.
 *
 * @param approvalTimeoutSecs how long an approval request this policy raises stays open, in
 *                            seconds (1 to {@link #MAX_APPROVAL_TIMEOUT_SECS}). When it passes
 *                            the engine records {@code ApprovalExpired}: a rejection, never an
 *                            approval. Every level is a ceiling, so the shortest timeout any
 *                            level sets applies. {@code null}: no deadline at this level, and
 *                            the field is not written, so the policy's hash is unchanged.
 * @param escalateAfterSecs   seconds after a request is raised to record
 *                            {@code ApprovalEscalated} for notifiers. Needs
 *                            {@code approvalTimeoutSecs} at the same level and must be shorter.
 */
public record PolicySetIr(
        List<String> blockedTools,
        List<String> requireApprovalFor,
        List<String> modelAllowlist,
        Long approvalTimeoutSecs,
        Long escalateAfterSecs
) {
    /** One year, as in the Rust runtime: a typo cannot park a request forever. */
    public static final long MAX_APPROVAL_TIMEOUT_SECS = 365L * 24 * 60 * 60;

    /**
     * @throws IllegalArgumentException when the deadline settings fail the Rust runtime's
     *                                  {@code validate_approval_deadline}; the engine would
     *                                  refuse the IR, and skipping a level could lengthen a
     *                                  deadline
     */
    public PolicySetIr {
        blockedTools = blockedTools == null ? List.of() : List.copyOf(blockedTools);
        requireApprovalFor = requireApprovalFor == null ? List.of() : List.copyOf(requireApprovalFor);
        modelAllowlist = modelAllowlist == null ? List.of() : List.copyOf(modelAllowlist);
        validateApprovalDeadline(approvalTimeoutSecs, escalateAfterSecs);
    }

    /** A policy with no approval deadline. */
    public PolicySetIr(List<String> blockedTools, List<String> requireApprovalFor, List<String> modelAllowlist) {
        this(blockedTools, requireApprovalFor, modelAllowlist, null, null);
    }

    public static PolicySetIr empty() {
        return new PolicySetIr(List.of(), List.of(), List.of());
    }

    /** This policy with an approval deadline (and optional escalation) set. */
    public PolicySetIr withApprovalDeadline(long timeoutSecs, Long escalateAfterSecs) {
        return new PolicySetIr(blockedTools, requireApprovalFor, modelAllowlist, timeoutSecs, escalateAfterSecs);
    }

    private static void validateApprovalDeadline(Long timeout, Long escalate) {
        if (timeout == null) {
            if (escalate != null) {
                throw new IllegalArgumentException(
                        "escalate_after_secs needs approval_timeout_secs in the same policy");
            }
            return;
        }
        if (timeout < 1 || timeout > MAX_APPROVAL_TIMEOUT_SECS) {
            throw new IllegalArgumentException("approval_timeout_secs must be between 1 and "
                    + MAX_APPROVAL_TIMEOUT_SECS + ", got " + timeout);
        }
        if (escalate != null && (escalate < 1 || escalate >= timeout)) {
            throw new IllegalArgumentException("escalate_after_secs must be at least 1 and less than "
                    + "approval_timeout_secs (" + timeout + "), got " + escalate);
        }
    }
}
