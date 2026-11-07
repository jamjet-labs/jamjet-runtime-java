package dev.jamjet.agent.client;

import java.util.List;

/** The pending and decided approvals of one execution. */
public record ApprovalList(List<PendingApproval> pending, List<DecidedApproval> decided) {
    public ApprovalList {
        pending = pending == null ? List.of() : List.copyOf(pending);
        decided = decided == null ? List.of() : List.copyOf(decided);
    }
}
