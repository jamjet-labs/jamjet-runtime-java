package dev.jamjet.runtime.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SessionType {
    STATELESS("stateless"),
    RESUMABLE("resumable"),
    PERSISTENT_GOVERNED("persistent_governed"),
    EPHEMERAL("ephemeral"),
    APPROVAL_GATED("approval_gated");

    private final String value;

    SessionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SessionType fromValue(String value) {
        for (SessionType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown SessionType: " + value);
    }
}
