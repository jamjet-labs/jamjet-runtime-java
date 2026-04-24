package dev.jamjet.runtime.core.ir;

import java.util.Map;

public record NodeDef(
        String id,
        NodeKind kind,
        String retryPolicy,
        Long nodeTimeoutSecs,
        String description,
        Map<String, String> labels,
        PolicySetIr policy,
        DataPolicyIr dataPolicy
) {
    public NodeDef {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
