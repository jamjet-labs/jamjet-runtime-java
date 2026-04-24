package dev.jamjet.runtime.core.ir;

import java.util.List;

public record DataPolicyIr(
        List<String> piiFields,
        List<String> piiDetectors,
        String redactionMode,
        boolean retainPrompts,
        boolean retainOutputs,
        Integer retentionDays
) {
    public DataPolicyIr {
        piiFields = piiFields == null ? List.of() : List.copyOf(piiFields);
        piiDetectors = piiDetectors == null ? List.of() : List.copyOf(piiDetectors);
        redactionMode = redactionMode == null ? "mask" : redactionMode;
    }

    public static DataPolicyIr defaults() {
        return new DataPolicyIr(List.of(), List.of(), "mask", false, true, null);
    }
}
