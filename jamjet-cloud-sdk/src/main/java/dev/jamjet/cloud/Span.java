package dev.jamjet.cloud;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One span in a trace. Mirrors the Python SDK's Span dataclass; serializes
 * to the same JSON shape so the cloud API sees an identical event from
 * Python and Java agents.
 */
public final class Span {

    private final String traceId;
    private final String spanId;
    private final String kind;
    private final String name;
    private final int sequence;
    private final Instant timestamp;
    private final long startNanos;
    private final String agentName;
    private final String agentCardUri;

    private Long durationMs;
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    private Double costUsd;
    private String status = "pending";
    private String failureMode;

    Span(String traceId, String spanId, String kind, String name, int sequence,
         String agentName, String agentCardUri) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.kind = kind;
        this.name = name;
        this.sequence = sequence;
        this.timestamp = Instant.now();
        this.startNanos = System.nanoTime();
        this.agentName = agentName;
        this.agentCardUri = agentCardUri;
    }

    public Span model(String v)         { this.model = v; return this; }
    public Span inputTokens(long v)     { this.inputTokens = v; return this; }
    public Span outputTokens(long v)    { this.outputTokens = v; return this; }
    public Span costUsd(double v)       { this.costUsd = v; return this; }

    /** Mark the span complete with status="ok" and emit it. */
    public void finish() {
        finish("ok");
    }

    public void finish(String status) {
        this.status = status;
        if (this.durationMs == null) {
            long elapsedNs = System.nanoTime() - startNanos;
            this.durationMs = elapsedNs / 1_000_000L;
        }
        JamjetCloud.emit(this);
    }

    /**
     * Mark the span failed with a typed failure mode. Valid modes match the
     * server's events_failure_mode_check constraint.
     */
    public void fail(String mode) {
        this.failureMode = mode;
        finish("error");
    }

    Map<String, Object> toEventMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "span");
        m.put("trace_id", traceId);
        m.put("span_id", spanId);
        m.put("kind", kind);
        m.put("name", name);
        m.put("sequence", sequence);
        m.put("timestamp", timestamp.toString());
        m.put("status", status);
        if (durationMs != null) m.put("duration_ms", durationMs);
        if (model != null) m.put("model", model);
        if (inputTokens != null) m.put("input_tokens", inputTokens);
        if (outputTokens != null) m.put("output_tokens", outputTokens);
        if (costUsd != null) m.put("cost_usd", costUsd);
        if (agentName != null) m.put("agent_name", agentName);
        if (agentCardUri != null) m.put("agent_card_uri", agentCardUri);
        if (failureMode != null) m.put("failure_mode", failureMode);
        return m;
    }

    static String newTraceId() {
        return "tr_" + UUID.randomUUID().toString().replace("-", "");
    }

    static String newSpanId() {
        return "sp_" + UUID.randomUUID().toString().replace("-", "");
    }
}
