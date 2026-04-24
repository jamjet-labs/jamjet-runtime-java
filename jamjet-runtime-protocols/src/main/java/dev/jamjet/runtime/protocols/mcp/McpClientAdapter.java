package dev.jamjet.runtime.protocols.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.protocols.ProtocolAdapter;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@link ProtocolAdapter} that wraps the official MCP Java SDK to call remote
 * MCP tools via stdio transport.
 *
 * <p>This class requires {@code io.modelcontextprotocol.sdk:mcp-core} to be on
 * the classpath at runtime.  If it is not present, constructing this adapter
 * will throw a {@link NoClassDefFoundError}.</p>
 *
 * <p>Usage:
 * <pre>{@code
 *   McpClientAdapter adapter = McpClientAdapter.ofStdio("uvx", List.of("mcp-server-name"));
 *   adapter.start();                          // connects + initializes
 *   List<McpSchema.Tool> tools = adapter.listTools();
 *   JsonNode result = adapter.callTool("my_tool", argumentsNode);
 *   adapter.stop();
 * }</pre>
 * </p>
 */
public final class McpClientAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpClientAdapter.class);

    private final ServerParameters serverParameters;
    private volatile McpSyncClient client;

    private McpClientAdapter(ServerParameters serverParameters) {
        this.serverParameters = serverParameters;
    }

    /**
     * Creates an adapter that spawns the MCP server as a subprocess using stdio transport.
     *
     * @param command the executable to run, e.g. {@code "uvx"} or {@code "node"}
     * @param args    additional command-line arguments
     * @return a new, unstarted {@link McpClientAdapter}
     */
    public static McpClientAdapter ofStdio(String command, List<String> args) {
        ServerParameters params = ServerParameters.builder(command)
                .args(args)
                .build();
        return new McpClientAdapter(params);
    }

    @Override
    public String protocol() {
        return "mcp";
    }

    /**
     * Connects to the MCP server and performs the MCP initialization handshake.
     *
     * @throws RuntimeException if the connection or initialization fails
     */
    @Override
    public void start() {
        log.debug("Starting McpClientAdapter for command '{}'", serverParameters.getCommand());
        StdioClientTransport transport =
                new StdioClientTransport(serverParameters, McpJsonDefaults.getMapper());
        McpSyncClient syncClient = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("jamjet-runtime", "0.1.0"))
                .build();
        syncClient.initialize();
        this.client = syncClient;
        log.info("McpClientAdapter started for command '{}'", serverParameters.getCommand());
    }

    /**
     * Disconnects from the MCP server and releases all resources.
     */
    @Override
    public void stop() {
        McpSyncClient c = this.client;
        if (c != null) {
            log.debug("Stopping McpClientAdapter");
            c.close();
            this.client = null;
        }
    }

    /**
     * Lists all tools advertised by the connected MCP server.
     *
     * @return list of tools
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public List<McpSchema.Tool> listTools() {
        return requireClient().listTools().tools();
    }

    /**
     * Calls a named tool on the MCP server.
     *
     * @param toolName  the name of the tool to call
     * @param arguments JSON arguments to pass (may be null → no arguments)
     * @return the result as a {@link JsonNode}; the returned node is an object whose
     *         {@code content} field is an array of content items and whose
     *         {@code is_error} field is set when the tool reported an error
     * @throws IllegalStateException    if {@link #start()} has not been called
     * @throws McpToolCallException     if the tool returns an error result
     */
    public JsonNode callTool(String toolName, JsonNode arguments) {
        Map<String, Object> args = jsonNodeToMap(arguments);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
        McpSchema.CallToolResult result = requireClient().callTool(request);

        if (Boolean.TRUE.equals(result.isError())) {
            String msg = extractText(result);
            throw new McpToolCallException("MCP tool '" + toolName + "' returned error: " + msg);
        }

        // Serialize result to a JsonNode for use in the JamJet runtime
        return serializeResult(result);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private McpSyncClient requireClient() {
        McpSyncClient c = this.client;
        if (c == null) {
            throw new IllegalStateException("McpClientAdapter has not been started — call start() first");
        }
        return c;
    }

    /** Converts a {@link JsonNode} object into a {@code Map<String,Object>}. */
    private static Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> map = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            map.put(entry.getKey(), entry.getValue().isTextual()
                    ? entry.getValue().asText()
                    : entry.getValue());
        }
        return map;
    }

    /** Extracts text from the first TextContent item in the result. */
    private static String extractText(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "<no content>";
        }
        McpSchema.Content first = result.content().get(0);
        if (first instanceof McpSchema.TextContent tc) {
            return tc.text();
        }
        return first.toString();
    }

    /** Serializes a {@link McpSchema.CallToolResult} to a {@link JsonNode}. */
    private static JsonNode serializeResult(McpSchema.CallToolResult result) {
        ObjectNode root = JamjetJson.shared().createObjectNode();
        ArrayNode content = root.putArray("content");
        if (result.content() != null) {
            for (McpSchema.Content item : result.content()) {
                if (item instanceof McpSchema.TextContent tc) {
                    ObjectNode c = content.addObject();
                    c.put("type", "text");
                    c.put("text", tc.text());
                }
            }
        }
        if (result.isError() != null) {
            root.put("is_error", result.isError());
        }
        return root;
    }
}
