package dev.jamjet.runtime.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ApprovalDecision {
    APPROVED("approved"),
    REJECTED("rejected"),
    ESCALATE("escalate"),
    /**
     * A decision value this build does not know, read from an event log written by a later
     * release. It authorises nothing: a log holding one is treated as unreadable.
     */
    UNKNOWN("unknown");

    private final String value;

    ApprovalDecision(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** Reads an event log: an unknown value becomes {@link #UNKNOWN} instead of failing the log. */
    @JsonCreator
    public static ApprovalDecision fromJson(String value) {
        for (ApprovalDecision decision : values()) {
            if (decision != UNKNOWN && decision.value.equals(value)) {
                return decision;
            }
        }
        return UNKNOWN;
    }

    /** Parses caller input strictly. */
    public static ApprovalDecision fromValue(String value) {
        for (ApprovalDecision decision : values()) {
            if (decision != UNKNOWN && decision.value.equals(value)) {
                return decision;
            }
        }
        throw new IllegalArgumentException("Unknown ApprovalDecision: " + value);
    }
}
