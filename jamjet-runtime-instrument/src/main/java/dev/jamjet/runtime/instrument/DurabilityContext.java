package dev.jamjet.runtime.instrument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thread-local context that implements replay-or-execute semantics for durable agents.
 *
 * <p>During normal (non-replay) execution, calling {@link #replayOrExecute} invokes the
 * supplied computation and records its result under the given checkpoint ID. During replay,
 * if a result is already stored for a checkpoint ID it is returned directly without
 * re-invoking the supplier.</p>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * DurabilityContext ctx = DurabilityContext.create();
 * DurabilityContext.setCurrent(ctx);
 * try {
 *     String result = ctx.replayOrExecute("myCheckpoint", () -> expensiveCall());
 * } finally {
 *     DurabilityContext.clear();
 * }
 * }</pre>
 */
public final class DurabilityContext {

    private static final ThreadLocal<DurabilityContext> CURRENT = new ThreadLocal<>();

    private boolean replayMode = false;
    private final Map<String, Object> results = new LinkedHashMap<>();
    private final List<String> checkpointIds = new ArrayList<>();

    private DurabilityContext() {}

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /** Creates a new, empty {@code DurabilityContext} in normal (non-replay) mode. */
    public static DurabilityContext create() {
        return new DurabilityContext();
    }

    // -------------------------------------------------------------------------
    // Thread-local management
    // -------------------------------------------------------------------------

    /** Returns the context bound to the current thread, or {@code null} if none. */
    public static DurabilityContext current() {
        return CURRENT.get();
    }

    /** Binds {@code ctx} to the current thread. */
    public static void setCurrent(DurabilityContext ctx) {
        CURRENT.set(ctx);
    }

    /** Removes the context bound to the current thread. */
    public static void clear() {
        CURRENT.remove();
    }

    // -------------------------------------------------------------------------
    // Replay mode
    // -------------------------------------------------------------------------

    /** Returns {@code true} if this context is in replay mode. */
    public boolean isReplayMode() {
        return replayMode;
    }

    /**
     * Enables or disables replay mode.
     * In replay mode, {@link #replayOrExecute} returns previously recorded results
     * instead of invoking the supplier.
     */
    public void setReplayMode(boolean replayMode) {
        this.replayMode = replayMode;
    }

    // -------------------------------------------------------------------------
    // Core replay-or-execute
    // -------------------------------------------------------------------------

    /**
     * Returns the previously recorded result for {@code checkpointId} when in replay mode
     * and a result exists; otherwise invokes {@code supplier}, records its result, and
     * returns it.
     *
     * @param checkpointId stable identifier for the checkpoint
     * @param supplier     computation to execute when no cached result is available
     * @param <T>          return type
     * @return the recorded or freshly computed result
     */
    @SuppressWarnings("unchecked")
    public <T> T replayOrExecute(String checkpointId, Supplier<T> supplier) {
        if (replayMode && results.containsKey(checkpointId)) {
            return (T) results.get(checkpointId);
        }
        T value = supplier.get();
        recordResult(checkpointId, value);
        return value;
    }

    // -------------------------------------------------------------------------
    // Result storage
    // -------------------------------------------------------------------------

    /**
     * Records a result under the given {@code checkpointId}.
     * New IDs are appended to the ordered checkpoint list.
     */
    public void recordResult(String checkpointId, Object result) {
        if (!results.containsKey(checkpointId)) {
            checkpointIds.add(checkpointId);
        }
        results.put(checkpointId, result);
    }

    /**
     * Returns the stored result for {@code checkpointId}, or {@code null} if not recorded.
     */
    public Object getRecordedResult(String checkpointId) {
        return results.get(checkpointId);
    }

    /**
     * Returns the ordered list of checkpoint IDs that have been recorded in this context.
     */
    public List<String> getCheckpointIds() {
        return Collections.unmodifiableList(checkpointIds);
    }
}
