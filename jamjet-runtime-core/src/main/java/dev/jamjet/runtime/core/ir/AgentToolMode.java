package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AgentToolMode {
    SYNC("sync"),
    ASYNC("async");

    private final String value;

    AgentToolMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AgentToolMode fromValue(String value) {
        for (AgentToolMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown AgentToolMode: " + value);
    }
}
