package dev.jamjet.runtime.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum QueueType {
    MODEL("model"),
    TOOL("tool"),
    PYTHON_TOOL("python_tool"),
    RETRIEVAL("retrieval"),
    PRIVILEGED("privileged"),
    GENERAL("general");

    private final String value;

    QueueType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static QueueType fromValue(String value) {
        for (QueueType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown QueueType: " + value);
    }
}
