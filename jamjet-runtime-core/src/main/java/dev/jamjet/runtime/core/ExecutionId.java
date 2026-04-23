package dev.jamjet.runtime.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.UUID;

public record ExecutionId(@JsonValue UUID value) {

    public static ExecutionId create() {
        return new ExecutionId(UUID.randomUUID());
    }

    public static ExecutionId of(UUID uuid) {
        return new ExecutionId(uuid);
    }

    @JsonCreator
    public static ExecutionId fromString(String raw) {
        return new ExecutionId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return "exec_" + value.toString().replace("-", "");
    }
}
