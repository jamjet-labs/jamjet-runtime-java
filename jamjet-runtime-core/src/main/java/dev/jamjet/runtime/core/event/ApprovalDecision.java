package dev.jamjet.runtime.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ApprovalDecision {
    APPROVED("approved"),
    REJECTED("rejected"),
    ESCALATE("escalate");

    private final String value;

    ApprovalDecision(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ApprovalDecision fromValue(String value) {
        for (ApprovalDecision decision : values()) {
            if (decision.value.equals(value)) {
                return decision;
            }
        }
        throw new IllegalArgumentException("Unknown ApprovalDecision: " + value);
    }
}
