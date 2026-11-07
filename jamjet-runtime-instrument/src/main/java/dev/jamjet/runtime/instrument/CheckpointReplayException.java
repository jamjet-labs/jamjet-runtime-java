package dev.jamjet.runtime.instrument;

/**
 * Thrown on replay of a checkpointed call that failed when it first ran, when the
 * original exception object is no longer available (the record was restored from
 * storage). The original exception's class name and message are kept.
 */
public final class CheckpointReplayException extends RuntimeException {

    private final String checkpointId;
    private final String originalType;
    private final String originalMessage;

    public CheckpointReplayException(String checkpointId, String originalType, String originalMessage) {
        super("checkpoint " + checkpointId + " failed when it first ran: " + originalType
                + (originalMessage == null ? "" : ": " + originalMessage));
        this.checkpointId = checkpointId;
        this.originalType = originalType;
        this.originalMessage = originalMessage;
    }

    public String checkpointId() {
        return checkpointId;
    }

    /** Class name of the exception the call threw the first time. */
    public String originalType() {
        return originalType;
    }

    /** Message of the exception the call threw the first time, may be {@code null}. */
    public String originalMessage() {
        return originalMessage;
    }
}
