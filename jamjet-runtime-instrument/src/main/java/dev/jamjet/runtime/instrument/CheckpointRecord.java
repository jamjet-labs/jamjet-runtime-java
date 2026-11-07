package dev.jamjet.runtime.instrument;

import java.util.Objects;

/**
 * The recorded outcome of one checkpointed call: either the value it returned or the
 * failure it threw. A failure is never stored as a value, so it can never replay as a
 * successful return.
 *
 * <p>{@code failure} holds the original exception when the record was made in this
 * process. A record restored from storage carries only {@code failureType} and
 * {@code failureMessage}; replaying it throws {@link CheckpointReplayException}.</p>
 *
 * @param id             the checkpoint id the outcome is stored under
 * @param value          the returned value (always {@code null} for a failure)
 * @param failed         {@code true} when the call threw
 * @param failure        the thrown exception, when still available in this process
 * @param failureType    class name of the thrown exception ({@code null} for a success)
 * @param failureMessage message of the thrown exception (may be {@code null})
 */
public record CheckpointRecord(
        String id,
        Object value,
        boolean failed,
        Throwable failure,
        String failureType,
        String failureMessage
) {

    public CheckpointRecord {
        Objects.requireNonNull(id, "id");
        if (failed) {
            if (value != null) {
                throw new IllegalArgumentException("a failure record carries no value: " + id);
            }
            if (failure == null && failureType == null) {
                throw new IllegalArgumentException("a failure record needs the exception or its type: " + id);
            }
            if (failure != null && failureType == null) {
                failureType = failure.getClass().getName();
                failureMessage = failure.getMessage();
            }
        } else if (failure != null || failureType != null) {
            throw new IllegalArgumentException("a success record carries no failure: " + id);
        }
    }

    /** A call that returned {@code value}. */
    public static CheckpointRecord success(String id, Object value) {
        return new CheckpointRecord(id, value, false, null, null, null);
    }

    /** A call that threw {@code failure} in this process. */
    public static CheckpointRecord failure(String id, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return new CheckpointRecord(id, null, true, failure, null, null);
    }

    /** A failure restored from storage, where only the exception's type and message survive. */
    public static CheckpointRecord failure(String id, String failureType, String failureMessage) {
        Objects.requireNonNull(failureType, "failureType");
        return new CheckpointRecord(id, null, true, null, failureType, failureMessage);
    }

    /**
     * The exception a replay of this failure throws: the original when it is still held,
     * otherwise a {@link CheckpointReplayException} naming the original type and message.
     */
    Throwable replayFailure() {
        if (!failed) {
            throw new IllegalStateException("not a failure record: " + id);
        }
        return failure != null ? failure : new CheckpointReplayException(id, failureType, failureMessage);
    }
}
