package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TenantStatus {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    ARCHIVED("archived");

    private final String value;

    TenantStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TenantStatus fromValue(String value) {
        for (TenantStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown TenantStatus: " + value);
    }
}
