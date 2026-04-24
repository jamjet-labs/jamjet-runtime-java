package dev.jamjet.runtime.protocols.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.executor.ExecutionResult;
import dev.jamjet.runtime.core.executor.NodeExecutionException;
import dev.jamjet.runtime.core.executor.NodeExecutor;
import dev.jamjet.runtime.core.state.WorkItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * {@link NodeExecutor} for {@code mcp_tool} nodes.
 *
 * <p>Reads {@code server}, {@code tool}, and {@code arguments} from the work item
 * payload, looks up a {@link ToolCaller} via the provided client-lookup function,
 * and delegates the tool call to it.</p>
 *
 * <h3>Expected payload shape</h3>
 * <pre>{@code
 * {
 *   "server":    "my-mcp-server",    // required
 *   "tool":      "do_something",     // required
 *   "arguments": { ... }             // optional, defaults to {}
 * }
 * }</pre>
 *
 * <h3>Retryability</h3>
 * <ul>
 *   <li>Missing server / tool → non-retryable (bad configuration)</li>
 *   <li>Server not found in registry → non-retryable</li>
 *   <li>Tool call throws {@link McpToolCallException} → retryable</li>
 *   <li>Any other exception → retryable</li>
 * </ul>
 */
public final class McpToolExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);

    /**
     * Functional interface for calling a single MCP tool.
     */
    @FunctionalInterface
    public interface ToolCaller {
        /**
         * Calls the named tool with the given JSON arguments.
         *
         * @param toolName  the tool to invoke
         * @param arguments the arguments to pass (may be null)
         * @return the tool result as a {@link JsonNode}
         */
        JsonNode callTool(String toolName, JsonNode arguments);
    }

    private final Function<String, ToolCaller> clientLookup;

    /**
     * Creates a new executor with the given client-lookup function.
     *
     * @param clientLookup maps a server name to a {@link ToolCaller};
     *                     should return {@code null} when the server is unknown
     */
    public McpToolExecutor(Function<String, ToolCaller> clientLookup) {
        if (clientLookup == null) {
            throw new NullPointerException("clientLookup must not be null");
        }
        this.clientLookup = clientLookup;
    }

    @Override
    public ExecutionResult execute(WorkItem item) throws NodeExecutionException {
        JsonNode payload = item.payload();

        // Extract required fields
        String server = extractRequiredString(payload, "server");
        String tool   = extractRequiredString(payload, "tool");
        JsonNode arguments = payload.has("arguments") ? payload.get("arguments") : null;

        // Resolve the ToolCaller
        ToolCaller caller = clientLookup.apply(server);
        if (caller == null) {
            throw new NodeExecutionException(
                    "No MCP client registered for server '" + server + "'",
                    false // not retryable — this is a configuration problem
            );
        }

        // Invoke the tool and return
        long start = System.currentTimeMillis();
        try {
            log.debug("Calling MCP tool '{}' on server '{}'", tool, server);
            JsonNode result = caller.callTool(tool, arguments);
            long durationMs = System.currentTimeMillis() - start;
            log.debug("MCP tool '{}' on server '{}' completed in {}ms", tool, server, durationMs);
            return ExecutionResult.simple(result, JamjetJson.shared().createObjectNode(), durationMs);
        } catch (McpToolCallException e) {
            long durationMs = System.currentTimeMillis() - start;
            log.warn("MCP tool '{}' on server '{}' returned error after {}ms: {}",
                    tool, server, durationMs, e.getMessage());
            throw new NodeExecutionException(e.getMessage(), true, e);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("MCP tool '{}' on server '{}' threw after {}ms: {}",
                    tool, server, durationMs, e.getMessage(), e);
            throw new NodeExecutionException(
                    "MCP tool '" + tool + "' on server '" + server + "' failed: " + e.getMessage(),
                    true,
                    e
            );
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String extractRequiredString(JsonNode payload, String field) throws NodeExecutionException {
        if (payload == null || !payload.has(field) || payload.get(field).isNull()) {
            throw new NodeExecutionException(
                    "mcp_tool node payload missing required field: '" + field + "'",
                    false
            );
        }
        String value = payload.get(field).asText();
        if (value.isBlank()) {
            throw new NodeExecutionException(
                    "mcp_tool node payload field '" + field + "' must not be blank",
                    false
            );
        }
        return value;
    }
}
