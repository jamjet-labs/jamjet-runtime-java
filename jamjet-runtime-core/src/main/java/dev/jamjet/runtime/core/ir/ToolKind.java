package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ToolKind {
    PYTHON("python"),
    HTTP("http"),
    GRPC("grpc"),
    MCP("mcp");

    private final String value;

    ToolKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ToolKind fromValue(String value) {
        for (ToolKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown ToolKind: " + value);
    }
}
