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
        Long leaseFence
) {}
