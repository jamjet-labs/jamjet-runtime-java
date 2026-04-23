package dev.jamjet.runtime.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.EnumSet;
import java.util.Set;

public enum NodeStatus {
    PENDING("pending"),
    SCHEDULED("scheduled"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    SKIPPED("skipped"),
    CANCELLED("cancelled");

    private final String value;

    NodeStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static NodeStatus fromValue(String value) {
        for (NodeStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown NodeStatus: " + value);
    }

    private static final Set<NodeStatus> TERMINAL =
            EnumSet.of(COMPLETED, FAILED, SKIPPED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
