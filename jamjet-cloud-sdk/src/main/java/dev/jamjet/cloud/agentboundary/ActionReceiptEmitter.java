package dev.jamjet.cloud.agentboundary;

/**
 * Strategy for emitting AgentBoundary Action Receipts to an external sink.
 *
 * <p>v1.8 ships {@link LoggingActionReceiptEmitter} as the default implementation
 * (SLF4J INFO with structured JSON). v1.9 will add a {@code CloudActionReceiptEmitter}
 * that POSTs receipts to {@code api.jamjet.dev}. Customer implementations can emit
 * to any sink (Kafka, S3, a SIEM, an audit database, etc.).
 *
 * <p>Implementations MUST be thread-safe. Multiple advisor threads may call
 * {@link #emit} concurrently from different agent invocations.
 */
public interface ActionReceiptEmitter {

    /**
     * Emit a receipt.
     *
     * <p>Implementations SHOULD NOT throw — emission failures should be logged
     * internally but must not interrupt the agent's tool execution path. If emission
     * is critical (e.g., a compliance pipeline that must block on failure),
     * implementations may throw a {@link RuntimeException}, but callers should be aware
     * that doing so breaks the agent's execution.
     *
     * @param receipt the Action Receipt to emit; never {@code null}
     */
    void emit(ActionReceipt receipt);
}
