package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ViolationAction.Fail.class, name = "fail"),
        @JsonSubTypes.Type(value = ViolationAction.Branch.class, name = "branch"),
        @JsonSubTypes.Type(value = ViolationAction.Warn.class, name = "warn")
})
public sealed interface ViolationAction {
    record Fail() implements ViolationAction {}
    record Branch(String target) implements ViolationAction {}
    record Warn() implements ViolationAction {}
}
