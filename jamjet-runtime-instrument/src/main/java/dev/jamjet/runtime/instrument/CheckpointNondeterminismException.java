package dev.jamjet.runtime.instrument;

/**
 * Thrown on replay when a {@code @Checkpoint} call at a recorded position has different
 * arguments from the call recorded there. The run is not doing what it did the first time,
 * so neither answer is safe: returning the recorded result would answer a different call,
 * and running the body would repeat a side effect that already happened. The call is
 * refused instead, as Temporal does for a nondeterministic workflow.
 *
 * <p>Usual causes: an argument computed outside any checkpoint that changes between runs
 * (a random id, the current time), or code that changed between the runs. Compute such
 * values inside a checkpoint so they replay too.</p>
 *
 * <p>Extends {@link RuntimeException} directly, so a {@code catch (IllegalStateException)}
 * written for business errors does not swallow it.</p>
 */
public final class CheckpointNondeterminismException extends RuntimeException {

    private final String position;
    private final String recordedId;
    private final String actualId;

    public CheckpointNondeterminismException(String position, String recordedId, String actualId) {
        super("nondeterministic replay at checkpoint " + position + ": recorded " + recordedId
                + " but this run made " + actualId + "; the arguments differ from the first run");
        this.position = position;
        this.recordedId = recordedId;
        this.actualId = actualId;
    }

    /** The checkpoint name and call position, without the argument fingerprint. */
    public String position() {
        return position;
    }

    /** The id recorded at that position. */
    public String recordedId() {
        return recordedId;
    }

    /** The id this run's call has. */
    public String actualId() {
        return actualId;
    }
}
