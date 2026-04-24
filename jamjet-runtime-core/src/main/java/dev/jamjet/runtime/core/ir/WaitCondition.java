package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum WaitCondition {
    TIMER("timer"),
    EXTERNAL_EVENT("external_event"),
    EITHER("either");

    private final String value;

    WaitCondition(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static WaitCondition fromValue(String value) {
        for (WaitCondition condition : values()) {
            if (condition.value.equals(value)) {
                return condition;
            }
        }
        throw new IllegalArgumentException("Unknown WaitCondition: " + value);
    }
}
