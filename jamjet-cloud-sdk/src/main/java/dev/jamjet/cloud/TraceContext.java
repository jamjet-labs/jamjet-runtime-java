package dev.jamjet.cloud;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-context (per-thread / per-virtual-thread) trace state. Holds the
 * current trace_id and increments span sequence numbers.
 */
final class TraceContext {

    private static final ThreadLocal<TraceContext> CURRENT = ThreadLocal.withInitial(TraceContext::new);

    private final String traceId = Span.newTraceId();
    private final AtomicInteger seq = new AtomicInteger(0);

    static TraceContext current() {
        return CURRENT.get();
    }

    static void reset() {
        CURRENT.remove();
    }

    String traceId() {
        return traceId;
    }

    Span newSpan(String kind, String name) {
        Agent active = AgentContext.current();
        return new Span(
                traceId,
                Span.newSpanId(),
                kind,
                name,
                seq.incrementAndGet(),
                active != null ? active.name() : null,
                active != null ? active.cardUri() : null
        );
    }
}
