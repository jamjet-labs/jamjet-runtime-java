package dev.jamjet.agent.client;

import java.util.Map;

/**
 * A work item claimed from the engine via {@code POST /work-items/claim}.
 * Mirrors the {@code work_item} object the Python client returns
 * ({@code {id, execution_id, node_id, queue_type, payload, attempt, lease_fence}}).
 *
 * <p>{@code leaseFence} (added by engine PR #108) MUST be threaded back on both
 * {@code heartbeat} and {@code complete} so a reclaimed worker cannot duplicate a
 * completion: a stale fence yields HTTP 409. It is {@code null} only for legacy
 * unfenced claims.
 *
 * <p>{@code idempotencyKey} (added by engine PR #128) is the key the engine derived
 * for THIS node occurrence. Echo it on {@code complete} and the engine records the
 * result against it, so a re-run replays instead of firing the tool a second time.
 * Without it nothing lands in {@code tool_effects} and every replay re-fires.
 * {@code null} against an engine that predates the field.
 *
 * <p>For a {@code java_tool} item the {@code payload} carries the enriched
 * {@code class} / {@code method} / {@code input} the durable tool-worker dispatches on.
 */
public record ClaimedWorkItem(
        String id,
        String executionId,
        String nodeId,
        String queueType,
        Map<String, Object> payload,
        int attempt,
        Long leaseFence,
        String idempotencyKey
) {
    /**
     * The pre-{@code idempotencyKey} constructor, kept so adding the component stays
     * source- and binary-compatible.
     *
     * <p>A record's canonical constructor is public API: widening it would break any
     * caller that builds a {@code ClaimedWorkItem} directly, even though every accessor
     * still resolves.
     */
    public ClaimedWorkItem(String id,
                           String executionId,
                           String nodeId,
                           String queueType,
                           Map<String, Object> payload,
                           int attempt,
                           Long leaseFence) {
        this(id, executionId, nodeId, queueType, payload, attempt, leaseFence, null);
    }
}
