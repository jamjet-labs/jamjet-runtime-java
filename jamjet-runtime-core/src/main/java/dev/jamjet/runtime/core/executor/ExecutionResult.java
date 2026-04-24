package dev.jamjet.runtime.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.event.ProvenanceMetadata;

/**
 * Result returned by a {@link NodeExecutor} after successful execution.
 *
 * @param output         the node output value
 * @param statePatch     JSON merge patch to apply to workflow state
 * @param durationMs     execution duration in milliseconds
 * @param genAiSystem    AI provider, e.g. "anthropic", "openai" (nullable)
 * @param genAiModel     model name (nullable)
 * @param inputTokens    input token count (nullable)
 * @param outputTokens   output token count (nullable)
 * @param finishReason   model finish reason, e.g. "stop" (nullable)
 * @param costUsd        estimated cost in USD (nullable)
 * @param provenance     provenance metadata (nullable)
 */
public record ExecutionResult(
        JsonNode output,
        JsonNode statePatch,
        long durationMs,
        String genAiSystem,
        String genAiModel,
        Long inputTokens,
        Long outputTokens,
        String finishReason,
        Double costUsd,
        ProvenanceMetadata provenance
) {

    /**
     * Creates a simple result with no AI telemetry fields.
     */
    public static ExecutionResult simple(JsonNode output, JsonNode statePatch, long durationMs) {
        return new ExecutionResult(output, statePatch, durationMs,
                null, null, null, null, null, null, null);
    }
}
