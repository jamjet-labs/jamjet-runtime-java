package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FinalizerTrigger {
    SUCCESS("success"),
    FAILURE("failure"),
    ALWAYS("always");

    private final String value;

    FinalizerTrigger(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static FinalizerTrigger fromValue(String value) {
        for (FinalizerTrigger trigger : values()) {
            if (trigger.value.equals(value)) {
                return trigger;
            }
        }
        throw new IllegalArgumentException("Unknown FinalizerTrigger: " + value);
    }
}
