package dev.jamjet.runtime.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum QueueType {
    MODEL("model"),
    TOOL("tool"),
    PYTHON_TOOL("python_tool"),
    JAVA_TOOL("java_tool"),
    RETRIEVAL("retrieval"),
    PRIVILEGED("privileged"),
    GENERAL("general"),
    /**
     * A queue type this build does not know, read from an event log (the Rust runtime takes
     * any string on {@code POST /work-items}). No Java worker serves it.
     */
    UNKNOWN("unknown");

    private final String value;

    QueueType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** Reads an event log: an unknown value becomes {@link #UNKNOWN} instead of failing the log. */
    @JsonCreator
    public static QueueType fromJson(String value) {
        for (QueueType type : values()) {
            if (type != UNKNOWN && type.value.equals(value)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    /** Parses caller input strictly. */
    public static QueueType fromValue(String value) {
        for (QueueType type : values()) {
            if (type != UNKNOWN && type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown QueueType: " + value);
    }
}
