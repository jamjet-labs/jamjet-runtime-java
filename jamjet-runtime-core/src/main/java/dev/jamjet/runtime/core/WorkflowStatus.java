package dev.jamjet.runtime.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum WorkflowStatus {
    PENDING("pending"),
    RUNNING("running"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    LIMIT_EXCEEDED("limit_exceeded");

    private final String value;

    WorkflowStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static WorkflowStatus fromValue(String value) {
        for (WorkflowStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown WorkflowStatus: " + value);
    }

    private static final Set<WorkflowStatus> TERMINAL =
            EnumSet.of(COMPLETED, FAILED, CANCELLED, LIMIT_EXCEEDED);

    private static final Map<WorkflowStatus, Set<WorkflowStatus>> VALID_TRANSITIONS = Map.of(
            PENDING, EnumSet.of(RUNNING),
            RUNNING, EnumSet.of(PAUSED, COMPLETED, FAILED, CANCELLED, LIMIT_EXCEEDED),
            PAUSED, EnumSet.of(RUNNING, CANCELLED),
            COMPLETED, EnumSet.noneOf(WorkflowStatus.class),
            FAILED, EnumSet.noneOf(WorkflowStatus.class),
            CANCELLED, EnumSet.noneOf(WorkflowStatus.class),
            LIMIT_EXCEEDED, EnumSet.noneOf(WorkflowStatus.class)
    );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isActive() {
        return !isTerminal();
    }

    public boolean canTransitionTo(WorkflowStatus target) {
        return VALID_TRANSITIONS.getOrDefault(this, EnumSet.noneOf(WorkflowStatus.class))
                .contains(target);
    }
}
