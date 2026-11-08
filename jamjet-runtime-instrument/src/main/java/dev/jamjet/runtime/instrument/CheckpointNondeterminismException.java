package dev.jamjet.runtime.instrument;

/**
 * Thrown on replay when a run is not doing what it did the first time: a {@code @Checkpoint}
 * call at a recorded position has different arguments, or a position has no record while
 * later ones do. Neither answer is safe: returning the recorded result would answer a
 * different call, and running the body would repeat a side effect that already happened.
 *
 * <p>It is an {@link Error}, so a {@code catch (Exception)} retry loop in agent code does not
 * swallow it. And once a context has thrown it, every later checkpoint call in that context
 * throws it too (with the first as its cause), so even a {@code catch (Throwable)} loop
 * cannot reach the body by calling again at the next position.</p>
 *
 * <p>Usual causes: an argument computed outside any checkpoint that changes between runs (a
 * random id, the current time), a list filled from a {@code HashSet}'s iteration order, or
 * code that changed between the runs. Compute such values inside a checkpoint so they
 * replay too.</p>
 *
 * <p>To recover, start a fresh context. To keep the work recorded before the divergence,
 * restore only the records before the failing position (and the positions after it in the
 * same scope are dropped with them); the steps from there on run again.</p>
 */
public final class CheckpointNondeterminismException extends Error {

    private final String position;
    private final String recordedId;
    private final String actualId;

    public CheckpointNondeterminismException(String position, String recordedId, String actualId) {
        super(recordedId == null
                ? "nondeterministic replay at checkpoint " + position + ": no record at this position, but "
                        + "later positions in its scope have records; this run took a path the first did not"
                : "nondeterministic replay at checkpoint " + position + ": recorded " + recordedId
                        + " but this run made " + actualId + "; the arguments differ from the first run");
        this.position = position;
        this.recordedId = recordedId;
        this.actualId = actualId;
    }

    /** Thrown for every call in a context after the first divergence. */
    CheckpointNondeterminismException(CheckpointNondeterminismException first) {
        super("this durability context already diverged from its recorded run; start a fresh context", first);
        this.position = first.position;
        this.recordedId = first.recordedId;
        this.actualId = first.actualId;
    }

    /** The checkpoint name and call position, without the argument fingerprint. */
    public String position() {
        return position;
    }

    /** The id recorded at that position, or {@code null} when there was none. */
    public String recordedId() {
        return recordedId;
    }

    /** The id this run's call has. */
    public String actualId() {
        return actualId;
    }
}
