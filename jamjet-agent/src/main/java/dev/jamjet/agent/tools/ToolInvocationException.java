package dev.jamjet.agent.tools;

/**
 * Raised by {@link ToolDispatcher} when a <em>registered</em> {@code @Tool} method
 * throws during reflective invocation, or when the dispatch thread is interrupted
 * (the worker aborted it because the lease was lost). It carries the offending tool
 * name plus the underlying cause so the durable worker can fail the work item with a
 * faithful message (mirroring the Python dispatcher, which lets a tool exception
 * propagate out of {@code dispatch_tool_calls}).
 *
 * <p>An <em>unknown</em>/unregistered tool name does NOT raise this — it is surfaced
 * to the model as a clean {@code role: tool} error message instead, so a hallucinated
 * name cannot fail the run (and, critically, never triggers an arbitrary invocation).
 *
 * <p>The surfaced message stays generic (the tool name only). The raw cause is retained
 * as the exception {@code cause} (for stack traces) but deliberately NOT embedded in the
 * message text, because that message is logged and persisted to the engine via
 * {@code failWorkItem}, where a raw {@code cause.toString()} could leak sensitive detail.
 */
public final class ToolInvocationException extends RuntimeException {

    private final String toolName;

    public ToolInvocationException(String toolName, Throwable cause) {
        super("tool '" + toolName + "' failed", cause);
        this.toolName = toolName;
    }

    /** The name of the tool whose invocation failed. */
    public String toolName() {
        return toolName;
    }
}
