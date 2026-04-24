package dev.jamjet.runtime.protocols.mcp;

/**
 * Thrown when an MCP tool call returns an error result.
 */
public class McpToolCallException extends RuntimeException {

    public McpToolCallException(String message) {
        super(message);
    }

    public McpToolCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
