package dev.jamjet.runtime.core.ir;

import java.util.List;

public record PolicySetIr(
        List<String> blockedTools,
        List<String> requireApprovalFor,
        List<String> modelAllowlist
) {
    public PolicySetIr {
        blockedTools = blockedTools == null ? List.of() : List.copyOf(blockedTools);
        requireApprovalFor = requireApprovalFor == null ? List.of() : List.copyOf(requireApprovalFor);
        modelAllowlist = modelAllowlist == null ? List.of() : List.copyOf(modelAllowlist);
    }

    public static PolicySetIr empty() {
        return new PolicySetIr(List.of(), List.of(), List.of());
    }
}
