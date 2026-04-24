package dev.jamjet.runtime.core.executor;

/**
 * Checked exception thrown by a {@link NodeExecutor} when execution fails.
 * Carries a retryability flag so the runtime can decide whether to re-queue.
 */
public class NodeExecutionException extends Exception {

    private final boolean retryable;

    public NodeExecutionException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public NodeExecutionException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Returns {@code true} if the runtime should retry this operation
     * according to the node's retry policy.
     */
    public boolean isRetryable() {
        return retryable;
    }
}
