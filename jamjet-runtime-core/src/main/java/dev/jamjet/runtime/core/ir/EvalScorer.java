package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EvalScorer.LlmJudge.class, name = "llm_judge"),
        @JsonSubTypes.Type(value = EvalScorer.Assertion.class, name = "assertion"),
        @JsonSubTypes.Type(value = EvalScorer.Latency.class, name = "latency"),
        @JsonSubTypes.Type(value = EvalScorer.Cost.class, name = "cost"),
        @JsonSubTypes.Type(value = EvalScorer.Custom.class, name = "custom")
})
public sealed interface EvalScorer {
    record LlmJudge(String model, String rubric, double minScore) implements EvalScorer {}
    record Assertion(List<String> checks) implements EvalScorer {
        public Assertion {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }
    record Latency(long thresholdMs) implements EvalScorer {}
    record Cost(double thresholdUsd) implements EvalScorer {}
    record Custom(String module, JsonNode kwargs) implements EvalScorer {}
}
