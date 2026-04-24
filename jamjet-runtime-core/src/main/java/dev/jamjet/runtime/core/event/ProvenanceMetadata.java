package dev.jamjet.runtime.core.event;

import java.util.List;

public record ProvenanceMetadata(
        String modelId,
        String modelVersion,
        Double confidence,
        boolean verified,
        String source,
        String trustDomain,
        List<String> evidenceRefs
) {
    public ProvenanceMetadata {
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
