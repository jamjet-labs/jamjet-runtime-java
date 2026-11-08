package dev.jamjet.runtime.instrument;

import java.util.ArrayList;
import java.util.HashMap;
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
 * <p>The context is an in-memory map from checkpoint id to {@link CheckpointRecord}, kept
 * in the order ids were first recorded. Nothing is written to disk or a database:
 * surviving a restart means saving {@link #getRecords()} yourself and loading them into a
 * fresh context with {@link #restore}, then turning replay mode on.</p>
 *
 * <p>With the JamJet javaagent attached, {@code @Checkpoint} methods record here too, under
 * ids of the form {@code name#n@fingerprint}: the checkpoint name, the call's position
 * among calls with that name, and a fingerprint of the arguments. Replay matches a call by
 * name and position; if its arguments differ from the recorded ones it throws
 * {@link CheckpointNondeterminismException} rather than return another call's result or
 * repeat a side effect. The second {@code poll()} replays the second result. A call that
 * throws an {@link Exception} is recorded as a failure and replays by throwing again; it is
 * never replayed as a successful return.</p>
 *
 * <p>To persist a run, save each record's {@code id}, {@code failed} flag, and either its
 * {@code value} or its {@code failureType} and {@code failureMessage}; load them with
 * {@link #restore} ({@link CheckpointRecord#failure(String, String, String)} for a failure).
 * The id carries the argument fingerprint, so nothing else is needed. A restored failure
 * replays as an instance of its original exception class when that class can be loaded and
 * built from its message (otherwise the nearest superclass that can, with a suppressed
 * {@link CheckpointReplayException} naming the original), so {@code catch} blocks behave as
 * they did. Restore only records your own code saved: restoring builds exceptions by class
 * name.</p>
 *
 * <p>Records written by 0.4.0 under the old {@code @Checkpoint} ids ({@code Class.method} or
 * the bare {@code @Checkpoint} value) are not read as {@code @Checkpoint} records. In 0.4.0
 * the agent path failed with {@code IllegalAccessError} for any class outside this package,
 * so such records should not exist; ids you passed to {@link #replayOrExecute} are unchanged.</p>
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
    private final Map<String, CheckpointRecord> records = new LinkedHashMap<>();
    /** {@code name#n} of each {@code @Checkpoint} record to its full id. */
    private final Map<String, String> byPosition = new HashMap<>();
    private final Map<String, Integer> occurrences = new HashMap<>();
    private static final java.util.regex.Pattern CHECKPOINT_ID =
            java.util.regex.Pattern.compile("(.*#\\d+)@[0-9a-f]{32}");

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
    public synchronized boolean isReplayMode() {
        return replayMode;
    }

    /**
     * Enables or disables replay mode.
     * In replay mode, {@link #replayOrExecute} returns previously recorded results
     * instead of invoking the supplier.
     *
     * <p>Each call starts a new pass over the run: the per-call positions used in
     * {@code @Checkpoint} ids restart from zero, so the first call of a replay matches the
     * first call that was recorded.</p>
     */
    public synchronized void setReplayMode(boolean replayMode) {
        this.replayMode = replayMode;
        this.occurrences.clear();
    }

    // -------------------------------------------------------------------------
    // Core replay-or-execute
    // -------------------------------------------------------------------------

    /**
     * Returns the previously recorded result for {@code checkpointId} when in replay mode
     * and a result exists; otherwise invokes {@code supplier}, records its result, and
     * returns it.
     *
     * <p>A supplier that throws records nothing, so a replay runs it again. When the id
     * holds a recorded failure (written by a {@code @Checkpoint} call or restored), replay
     * throws that failure instead of running the supplier.</p>
     *
     * @param checkpointId stable identifier for the checkpoint
     * @param supplier     computation to execute when no cached result is available
     * @param <T>          return type
     * @return the recorded or freshly computed result
     */
    @SuppressWarnings("unchecked")
    public <T> T replayOrExecute(String checkpointId, Supplier<T> supplier) {
        CheckpointRecord recorded;
        synchronized (this) {
            recorded = replayMode ? records.get(checkpointId) : null;
        }
        if (recorded != null) {
            if (recorded.failed()) {
                throw CheckpointRuntime.sneakyThrow(recorded.replayFailure());
            }
            return (T) recorded.value();
        }
        T value = supplier.get();
        recordResult(checkpointId, value);
        return value;
    }

    // -------------------------------------------------------------------------
    // Result storage
    // -------------------------------------------------------------------------

    /**
     * Records a successful result under the given {@code checkpointId}.
     * New IDs are appended to the ordered checkpoint list; an existing id is replaced.
     */
    public void recordResult(String checkpointId, Object result) {
        restore(CheckpointRecord.success(checkpointId, result));
    }

    /**
     * Records that the call under {@code checkpointId} threw {@code failure}. A replay of
     * that id throws it again.
     */
    public void recordFailure(String checkpointId, Throwable failure) {
        restore(CheckpointRecord.failure(checkpointId, failure));
    }

    /**
     * Stores {@code record} as is: the way to load records saved from an earlier run.
     * New IDs are appended to the ordered checkpoint list; an existing id is replaced.
     */
    public synchronized void restore(CheckpointRecord record) {
        records.put(record.id(), record);
        java.util.regex.Matcher m = CHECKPOINT_ID.matcher(record.id());
        if (m.matches()) {
            byPosition.put(m.group(1), record.id());
        }
    }

    /**
     * The {@code @Checkpoint} record at {@code position} ({@code name#n}, as in its id before
     * the {@code @}), whatever its argument fingerprint, or {@code null}.
     */
    synchronized CheckpointRecord getRecordAtPosition(String position) {
        String id = byPosition.get(position);
        return id == null ? null : records.get(id);
    }

    /**
     * Returns the stored result for {@code checkpointId}, or {@code null} if not recorded.
     *
     * @throws IllegalStateException when the id holds a recorded failure; a failure has no
     *                               result, and reading it as {@code null} would let it be
     *                               saved and replayed as a success. Use {@link #getRecord}.
     */
    public synchronized Object getRecordedResult(String checkpointId) {
        CheckpointRecord record = records.get(checkpointId);
        if (record == null) {
            return null;
        }
        if (record.failed()) {
            throw new IllegalStateException("checkpoint " + checkpointId + " recorded a failure ("
                    + record.failureType() + "), not a result; read it with getRecord");
        }
        return record.value();
    }

    /** Returns the record for {@code checkpointId}, or {@code null} if none. */
    public synchronized CheckpointRecord getRecord(String checkpointId) {
        return records.get(checkpointId);
    }

    /** Returns every record, successes and failures, in the order their ids were first recorded. */
    public synchronized List<CheckpointRecord> getRecords() {
        return List.copyOf(records.values());
    }

    /**
     * Returns the ordered list of checkpoint IDs that have been recorded in this context,
     * successes and failures alike.
     */
    public synchronized List<String> getCheckpointIds() {
        return List.copyOf(new ArrayList<>(records.keySet()));
    }

    /**
     * Returns the position of this call among calls sharing {@code scope} in the current
     * pass, starting at zero, and advances it.
     */
    synchronized int nextOccurrence(String scope) {
        return occurrences.merge(scope, 1, Integer::sum) - 1;
    }
}
