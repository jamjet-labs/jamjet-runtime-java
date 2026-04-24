package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = MergeStrategy.Collect.class, name = "collect"),
        @JsonSubTypes.Type(value = MergeStrategy.First.class, name = "first"),
        @JsonSubTypes.Type(value = MergeStrategy.Custom.class, name = "custom")
})
public sealed interface MergeStrategy {
    record Collect() implements MergeStrategy {}
    record First() implements MergeStrategy {}
    record Custom(String functionRef) implements MergeStrategy {}
}
