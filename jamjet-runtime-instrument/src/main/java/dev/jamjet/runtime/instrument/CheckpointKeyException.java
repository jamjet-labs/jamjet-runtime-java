package dev.jamjet.runtime.instrument;

/**
 * Thrown before a {@code @Checkpoint} method body runs when one of its arguments has no
 * stable fingerprint, so the call cannot be told apart from a call with different
 * arguments. Running it unkeyed could replay another call's result, so the call is
 * refused instead.
 */
public final class CheckpointKeyException extends IllegalStateException {

    public CheckpointKeyException(String message) {
        super(message);
    }

    public CheckpointKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
