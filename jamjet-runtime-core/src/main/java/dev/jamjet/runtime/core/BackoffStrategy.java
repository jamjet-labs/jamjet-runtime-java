package dev.jamjet.runtime.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum BackoffStrategy {
    FIXED("fixed"),
    LINEAR("linear"),
    EXPONENTIAL("exponential");

    private final String value;

    BackoffStrategy(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static BackoffStrategy fromValue(String value) {
        for (BackoffStrategy strategy : values()) {
            if (strategy.value.equals(value)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unknown BackoffStrategy: " + value);
    }
}
