package dev.jamjet.runtime.protocols;

/**
 * SPI for pluggable protocol adapters.
 *
 * <p>An adapter encapsulates connection lifecycle for a single agent-communication
 * protocol (e.g. MCP, A2A).  Implementations must be thread-safe.</p>
 */
public interface ProtocolAdapter {

    /**
     * Returns the canonical protocol name, e.g. {@code "mcp"} or {@code "a2a"}.
     *
     * @return protocol name (never null)
     */
    String protocol();

    /**
     * Starts the adapter, establishing any required connections or listeners.
     *
     * @throws RuntimeException if the adapter cannot start
     */
    void start();

    /**
     * Stops the adapter, releasing all resources.
     *
     * <p>Implementations should be idempotent: calling {@code stop()} on an
     * already-stopped adapter must not throw.</p>
     */
    void stop();
}
