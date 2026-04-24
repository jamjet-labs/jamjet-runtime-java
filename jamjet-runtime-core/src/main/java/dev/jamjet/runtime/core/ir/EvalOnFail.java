package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EvalOnFail {
    RETRY_WITH_FEEDBACK("retry_with_feedback"),
    ESCALATE("escalate"),
    HALT("halt"),
    LOG_AND_CONTINUE("log_and_continue");

    private final String value;

    EvalOnFail(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EvalOnFail fromValue(String value) {
        for (EvalOnFail action : values()) {
            if (action.value.equals(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown EvalOnFail: " + value);
    }
}
