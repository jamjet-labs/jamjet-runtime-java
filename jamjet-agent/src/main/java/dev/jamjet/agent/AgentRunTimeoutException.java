package dev.jamjet.agent;

/**
 * Raised when a durable agent run ({@link Agent#runDurable(String)}) does not reach a
 * terminal state within the poll deadline ({@link RunOptions#timeout()}, or the
 * agent's {@link Agent#timeoutSeconds()} when unset). Mirrors the Python
 * {@code run_durable}, which raises {@code TimeoutError} when no terminal state is
 * reached within {@code self.limits.timeout_seconds}.
 *
 * <p>{@link #status()} carries the last status observed before the deadline
 * (typically {@code running}), to aid diagnosis of a stalled run.
 */
public final class AgentRunTimeoutException extends AgentRunException {

    public AgentRunTimeoutException(String lastStatus, String message) {
        super(lastStatus, message);
    }
}
