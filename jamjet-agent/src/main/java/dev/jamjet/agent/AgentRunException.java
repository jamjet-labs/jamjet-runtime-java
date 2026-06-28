package dev.jamjet.agent;

/**
 * Raised when a durable agent run ({@link Agent#runDurable(String)}) reaches a
 * terminal state that is not {@code completed} — i.e. {@code failed},
 * {@code cancelled}, or {@code limit_exceeded}. This mirrors the Python
 * {@code run_durable}, which raises {@code RuntimeError} for a non-{@code completed}
 * terminal state, so a budget breach or a policy denial surfaces loudly rather than
 * returning a hollow result.
 *
 * <p>{@link #status()} carries the terminal {@link dev.jamjet.runtime.core.workflow}
 * status string the engine reported ({@code failed} / {@code cancelled} /
 * {@code limit_exceeded}), so callers can branch on the failure mode (e.g. treat a
 * {@code limit_exceeded} budget breach differently from a hard {@code failed}).
 */
public class AgentRunException extends RuntimeException {

    private final String status;

    public AgentRunException(String status, String message) {
        super(message);
        this.status = status;
    }

    /** The terminal status the engine reported ({@code failed} / {@code cancelled} / {@code limit_exceeded}). */
    public String status() {
        return status;
    }
}
