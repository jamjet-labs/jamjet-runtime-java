package dev.jamjet.runtime.protocols.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.SessionType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.ApprovalDecision;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.StateBackendException;
import dev.jamjet.runtime.core.state.WorkflowExecution;
import dev.jamjet.runtime.protocols.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ProtocolAdapter} that exposes JamJet runtime capabilities as MCP tools.
 *
 * <p>This adapter does NOT use the MCP SDK for serving — it handles tool calls
 * by name and delegates to a {@link StateBackend}.  The actual MCP protocol
 * transport (stdio / SSE) is wired at a higher level and calls
 * {@link #handleToolCall(String, JsonNode)} to dispatch inbound requests.</p>
 *
 * <h3>Tools exposed</h3>
 * <ul>
 *   <li>{@code jamjet_run_workflow} — create a new workflow execution</li>
 *   <li>{@code jamjet_get_execution} — retrieve an execution by ID</li>
 *   <li>{@code jamjet_list_executions} — list executions, optionally filtered by status</li>
 *   <li>{@code jamjet_cancel_execution} — cancel a running execution</li>
 *   <li>{@code jamjet_get_events} — retrieve the event log for an execution</li>
 *   <li>{@code jamjet_approve} — submit an approval decision for a paused node</li>
 * </ul>
 */
public final class McpServerAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpServerAdapter.class);

    static final String TOOL_RUN_WORKFLOW    = "jamjet_run_workflow";
    static final String TOOL_GET_EXECUTION   = "jamjet_get_execution";
    static final String TOOL_LIST_EXECUTIONS = "jamjet_list_executions";
    static final String TOOL_CANCEL          = "jamjet_cancel_execution";
    static final String TOOL_GET_EVENTS      = "jamjet_get_events";
    static final String TOOL_APPROVE         = "jamjet_approve";

    private static final Set<String> TOOL_NAMES = Set.of(
            TOOL_RUN_WORKFLOW,
            TOOL_GET_EXECUTION,
            TOOL_LIST_EXECUTIONS,
            TOOL_CANCEL,
            TOOL_GET_EVENTS,
            TOOL_APPROVE
    );

    private final StateBackend state;

    /**
     * Creates a new server adapter backed by the given {@link StateBackend}.
     *
     * @param state the state backend to use
     */
    public McpServerAdapter(StateBackend state) {
        if (state == null) {
            throw new NullPointerException("state must not be null");
        }
        this.state = state;
    }

    @Override
    public String protocol() {
        return "mcp";
    }

    @Override
    public void start() {
        log.info("McpServerAdapter started — {} tools registered", TOOL_NAMES.size());
    }

    @Override
    public void stop() {
        log.info("McpServerAdapter stopped");
    }

    /**
     * Returns the set of tool names exposed by this adapter.
     *
     * @return immutable set of tool names
     */
    public Set<String> listToolNames() {
        return TOOL_NAMES;
    }

    /**
     * Dispatches an inbound tool call to the appropriate handler.
     *
     * @param toolName  the name of the tool to invoke
     * @param arguments the JSON arguments passed by the caller (may be null)
     * @return JSON response node; on unknown tool, returns an error node
     */
    public JsonNode handleToolCall(String toolName, JsonNode arguments) {
        JsonNode args = arguments != null ? arguments : JamjetJson.shared().createObjectNode();
        try {
            return switch (toolName) {
                case TOOL_RUN_WORKFLOW    -> handleRunWorkflow(args);
                case TOOL_GET_EXECUTION   -> handleGetExecution(args);
                case TOOL_LIST_EXECUTIONS -> handleListExecutions(args);
                case TOOL_CANCEL          -> handleCancelExecution(args);
                case TOOL_GET_EVENTS      -> handleGetEvents(args);
                case TOOL_APPROVE         -> handleApprove(args);
                default                   -> errorNode("unknown_tool", "Unknown tool: " + toolName);
            };
        } catch (StateBackendException e) {
            log.error("StateBackendException in tool '{}': {}", toolName, e.getMessage(), e);
            return errorNode("backend_error", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in tool '{}': {}", toolName, e.getMessage(), e);
            return errorNode("internal_error", e.getMessage());
        }
    }

    // ── Tool handlers ────────────────────────────────────────────────────────

    /**
     * {@code jamjet_run_workflow}
     *
     * <p>Required arguments: {@code workflow_id} (string), {@code version} (string).<br>
     * Optional: {@code input} (object).</p>
     */
    private JsonNode handleRunWorkflow(JsonNode args) throws StateBackendException {
        String workflowId = requireString(args, "workflow_id");
        String version    = requireString(args, "version");
        JsonNode input    = args.has("input") ? args.get("input") : JamjetJson.shared().createObjectNode();

        ExecutionId execId = ExecutionId.create();
        WorkflowExecution execution = new WorkflowExecution(
                execId,
                workflowId,
                version,
                WorkflowStatus.PENDING,
                input,
                JamjetJson.shared().createObjectNode(),
                Instant.now(),
                Instant.now(),
                null,
                SessionType.RESUMABLE
        );
        state.createExecution(execution);
        log.debug("Created execution {} for workflow {}:{}", execId, workflowId, version);

        ObjectNode result = JamjetJson.shared().createObjectNode();
        result.put("execution_id", execId.toString());
        result.put("workflow_id", workflowId);
        result.put("version", version);
        result.put("status", WorkflowStatus.PENDING.getValue());
        return result;
    }

    /**
     * {@code jamjet_get_execution}
     *
     * <p>Required: {@code execution_id} (string).</p>
     */
    private JsonNode handleGetExecution(JsonNode args) throws StateBackendException {
        ExecutionId execId = parseExecutionId(args, "execution_id");
        Optional<WorkflowExecution> opt = state.getExecution(execId);
        if (opt.isEmpty()) {
            return errorNode("not_found", "Execution not found: " + execId);
        }
        return serializeExecution(opt.get());
    }

    /**
     * {@code jamjet_list_executions}
     *
     * <p>Optional: {@code status} (string), {@code limit} (int), {@code offset} (int).</p>
     */
    private JsonNode handleListExecutions(JsonNode args) throws StateBackendException {
        WorkflowStatus statusFilter = null;
        if (args.has("status") && !args.get("status").isNull()) {
            statusFilter = WorkflowStatus.fromValue(args.get("status").asText());
        }
        int limit  = args.has("limit")  ? args.get("limit").asInt(50)  : 50;
        int offset = args.has("offset") ? args.get("offset").asInt(0)  : 0;

        List<WorkflowExecution> executions = state.listExecutions(statusFilter, limit, offset);
        ArrayNode array = JamjetJson.shared().createArrayNode();
        for (WorkflowExecution e : executions) {
            array.add(serializeExecution(e));
        }
        ObjectNode result = JamjetJson.shared().createObjectNode();
        result.set("executions", array);
        result.put("count", executions.size());
        return result;
    }

    /**
     * {@code jamjet_cancel_execution}
     *
     * <p>Required: {@code execution_id} (string).</p>
     */
    private JsonNode handleCancelExecution(JsonNode args) throws StateBackendException {
        ExecutionId execId = parseExecutionId(args, "execution_id");
        state.updateExecutionStatus(execId, WorkflowStatus.CANCELLED);
        log.debug("Cancelled execution {}", execId);

        ObjectNode result = JamjetJson.shared().createObjectNode();
        result.put("execution_id", execId.toString());
        result.put("status", WorkflowStatus.CANCELLED.getValue());
        return result;
    }

    /**
     * {@code jamjet_get_events}
     *
     * <p>Required: {@code execution_id} (string).<br>
     * Optional: {@code since_sequence} (long).</p>
     */
    private JsonNode handleGetEvents(JsonNode args) throws StateBackendException {
        ExecutionId execId = parseExecutionId(args, "execution_id");
        List<Event> eventList;
        if (args.has("since_sequence")) {
            long since = args.get("since_sequence").asLong(0);
            eventList = state.getEventsSince(execId, since);
        } else {
            eventList = state.getEvents(execId);
        }

        ArrayNode array = JamjetJson.shared().createArrayNode();
        for (Event ev : eventList) {
            try {
                array.add(JamjetJson.shared().valueToTree(ev));
            } catch (Exception e) {
                // Skip events that can't be serialized
                log.warn("Failed to serialize event {}: {}", ev.id(), e.getMessage());
            }
        }
        ObjectNode result = JamjetJson.shared().createObjectNode();
        result.set("events", array);
        result.put("count", eventList.size());
        return result;
    }

    /**
     * {@code jamjet_approve}
     *
     * <p>Required: {@code execution_id} (string), {@code node_id} (string),
     * {@code decision} (string: "approved"|"rejected").<br>
     * Optional: {@code user_id} (string), {@code comment} (string),
     * {@code state_patch} (object).</p>
     */
    private JsonNode handleApprove(JsonNode args) throws StateBackendException {
        ExecutionId execId = parseExecutionId(args, "execution_id");
        String nodeId      = requireString(args, "node_id");
        String decisionStr = requireString(args, "decision");
        ApprovalDecision decision = ApprovalDecision.fromValue(decisionStr.toLowerCase(java.util.Locale.ROOT));

        String userId   = args.has("user_id")  ? args.get("user_id").asText()  : "system";
        String comment  = args.has("comment")  ? args.get("comment").asText()  : null;
        JsonNode patch  = args.has("state_patch") ? args.get("state_patch") : null;

        EventKind kind = new EventKind.ApprovalReceived(nodeId, userId, decision, comment, patch);
        long sequence  = state.latestSequence(execId) + 1;
        Event event    = Event.create(execId, sequence, kind);
        state.appendEvent(event);
        log.debug("Approval {} recorded for execution {} node {}", decision, execId, nodeId);

        ObjectNode result = JamjetJson.shared().createObjectNode();
        result.put("execution_id", execId.toString());
        result.put("node_id", nodeId);
        result.put("decision", decision.name().toLowerCase());
        return result;
    }

    // ── Serialisation helpers ────────────────────────────────────────────────

    private static ObjectNode serializeExecution(WorkflowExecution e) {
        ObjectNode node = JamjetJson.shared().createObjectNode();
        node.put("execution_id", e.executionId().toString());
        node.put("workflow_id", e.workflowId());
        node.put("version", e.workflowVersion());
        node.put("status", e.status().getValue());
        node.put("started_at", e.startedAt() != null ? e.startedAt().toString() : null);
        if (e.completedAt() != null) {
            node.put("completed_at", e.completedAt().toString());
        }
        return node;
    }

    private static ObjectNode errorNode(String code, String message) {
        ObjectNode node = JamjetJson.shared().createObjectNode();
        node.put("error", code);
        node.put("message", message);
        return node;
    }

    private static String requireString(JsonNode args, String field) {
        JsonNode n = args.get(field);
        if (n == null || n.isNull() || n.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + field);
        }
        return n.asText();
    }

    private static ExecutionId parseExecutionId(JsonNode args, String field) {
        String raw = requireString(args, field);
        // Accept both "exec_<hex32>" and plain UUID strings
        if (raw.startsWith("exec_")) {
            String hex = raw.substring(5);
            if (hex.length() != 32 || !hex.chars().allMatch(c -> "0123456789abcdef".indexOf(c) >= 0)) {
                throw new IllegalArgumentException("Invalid execution ID format: " + raw);
            }
            // Re-insert dashes: 8-4-4-4-12
            String uuid = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
            return ExecutionId.fromString(uuid);
        }
        return ExecutionId.fromString(raw);
    }
}
