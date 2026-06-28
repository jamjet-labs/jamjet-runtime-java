package dev.jamjet.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.jamjet.agent.tools.RegisteredTool;
import dev.jamjet.agent.tools.ToolDispatcher;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.TimeoutConfig;
import dev.jamjet.runtime.core.ir.ConditionalBranch;
import dev.jamjet.runtime.core.ir.DataPolicyIr;
import dev.jamjet.runtime.core.ir.EdgeDef;
import dev.jamjet.runtime.core.ir.NodeDef;
import dev.jamjet.runtime.core.ir.NodeKind;
import dev.jamjet.runtime.core.ir.PolicySetIr;
import dev.jamjet.runtime.core.ir.TokenBudgetIr;
import dev.jamjet.runtime.core.ir.WorkflowIr;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles a {@link Agent} into a durable agent-loop {@link WorkflowIr}, mirroring
 * the Python {@code jamjet.compiler.agent_ir.compile_agent_to_ir} so a Java agent
 * and a Python agent produce the <em>same</em> graph for the Rust engine.
 *
 * <p>Because the model picks tools dynamically, the loop is <b>statically
 * unrolled</b> into {@code maxTurns} turns, each turn being three nodes:
 * <ol>
 *   <li>{@code __model_{t}__} — a {@link NodeKind.Model} carrying the agent's
 *       OpenAI tool schemas;</li>
 *   <li>{@code __tool_gate_{t}__} — a {@link NodeKind.Condition} on
 *       {@code state.last_model_finish_reason == "tool_calls"} (true &rarr; the
 *       turn's tool-dispatch node, false &rarr; the terminal {@code end});</li>
 *   <li>{@code __tools_{t}__} — a {@link NodeKind.JavaFn} pointing at the fixed
 *       Java tool dispatcher (the analog of Python's
 *       {@code jamjet.agents.tool_runtime:dispatch_tool_calls}); it routes to the
 *       next turn's model node.</li>
 * </ol>
 * A final tool-less {@code __model_{maxTurns}__} produces the answer and routes to
 * {@code end}, so the terminal is always reached via a model turn.
 *
 * <p><b>M2</b>: {@code start_node} is always {@code __model_0__} (the first Model
 * node), never a tool node.
 */
final class AgentIrCompiler {

    /**
     * The fixed Java tool-dispatch coordinate every {@code __tools_{t}__} node
     * carries — the Java analog of Python's
     * {@code jamjet.agents.tool_runtime:dispatch_tool_calls}. The Phase B-3 durable
     * worker resolves this dispatcher, which reads {@code last_model_tool_calls}
     * from state and fans out to the requested {@code @Tool} methods via the
     * {@link dev.jamjet.agent.tools.ToolRegistry}. (A single dispatcher per turn,
     * not one node per dynamic call, mirrors the Python loop exactly.)
     *
     * <p><b>Single source of truth.</b> These reference {@link ToolDispatcher}'s own
     * constants — the SAME constants the {@link dev.jamjet.agent.worker.JavaToolWorker}
     * RCE gate checks a claimed {@code java_fn} payload against. So the coordinate the
     * builder <em>emits</em> and the coordinate the worker <em>accepts</em> are one
     * literal and can never silently diverge (a B-3-review DRY fix).
     */
    static final String DISPATCH_CLASS = ToolDispatcher.DISPATCH_CLASS;
    static final String DISPATCH_METHOD = ToolDispatcher.DISPATCH_METHOD;

    /** The condition the tool gate branches on (matches the Model executor's recorded finish reason). */
    static final String TOOL_CALLS_EXPR = "state.last_model_finish_reason == \"tool_calls\"";

    /** Graph terminal sentinel (edges target it; it is not a node). */
    static final String END = "end";

    private static final String MODEL_RETRY_POLICY = "llm_default";
    private static final String TOOLS_RETRY_POLICY = "no_retry";
    private static final String VERSION_BASE = "0.1.0";
    private static final int HEARTBEAT_INTERVAL_SECS = 30;

    /** The five built-in PII detectors, matching the Rust PiiRedactor + the Python default. */
    private static final List<String> DEFAULT_PII_DETECTORS =
            List.of("email", "ssn", "credit_card", "phone", "ip_address");

    /**
     * Canonical (sorted-key) snake_case mapper used only to derive the immutable
     * content-version hash. Sorting map entries makes the hash independent of
     * {@code Map.of}/{@code LinkedHashMap} iteration order, so the same agent
     * always yields the same version across runs and JVMs.
     */
    private static final ObjectMapper CANON =
            JamjetJson.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private AgentIrCompiler() {}

    static WorkflowIr compile(Agent agent, int maxTurns) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be >= 1");
        }

        String modelRef = litellmModel(agent.model());
        List<Map<String, Object>> toolSchemas = agent.registry().openAiToolSchemas();
        String systemPrompt = blankToNull(agent.instructions());

        Map<String, NodeDef> nodes = new LinkedHashMap<>();
        List<EdgeDef> edges = new ArrayList<>();

        for (int t = 0; t < maxTurns; t++) {
            String modelId = "__model_" + t + "__";
            String gateId = "__tool_gate_" + t + "__";
            String toolsId = "__tools_" + t + "__";

            // Model node: carries the tool schemas, reads messages from state.
            nodes.put(modelId, node(
                    modelId,
                    new NodeKind.Model(modelRef, "", "", systemPrompt, toolSchemas),
                    MODEL_RETRY_POLICY,
                    "agent turn " + t + ": model call",
                    Map.of("jamjet.agent.loop", "model", "jamjet.agent.turn", String.valueOf(t))));
            edges.add(new EdgeDef(modelId, gateId, null));

            // Condition gate: tool_calls -> dispatch, else -> terminal answer.
            nodes.put(gateId, node(
                    gateId,
                    new NodeKind.Condition(List.of(
                            new ConditionalBranch(TOOL_CALLS_EXPR, toolsId),
                            new ConditionalBranch(null, END))),
                    null,
                    "agent turn " + t + ": tool-call gate",
                    Map.of("jamjet.agent.loop", "gate", "jamjet.agent.turn", String.valueOf(t))));
            edges.add(new EdgeDef(gateId, toolsId, TOOL_CALLS_EXPR));
            edges.add(new EdgeDef(gateId, END, null));

            // Tool-dispatch node: a JavaFn pointing at the fixed dispatcher.
            // no_retry: the dispatch runs user @Tool methods (possible
            // non-idempotent writes), so an already-succeeded dispatch must not
            // re-run on retry (mirrors the Python tool node).
            nodes.put(toolsId, node(
                    toolsId,
                    new NodeKind.JavaFn(DISPATCH_CLASS, DISPATCH_METHOD, ""),
                    TOOLS_RETRY_POLICY,
                    "agent turn " + t + ": dispatch tool calls",
                    Map.of("jamjet.agent.loop", "tools", "jamjet.agent.turn", String.valueOf(t))));
            // Always route forward to the NEXT model node (the last turn's dispatch
            // flows into the final model node below); a tool node never targets end.
            edges.add(new EdgeDef(toolsId, "__model_" + (t + 1) + "__", null));
        }

        // Final model node: no tool schemas, so the model must return a text
        // answer; routes straight to end. Guarantees the terminal is reached via
        // a model turn that saw the tool results.
        String finalId = "__model_" + maxTurns + "__";
        nodes.put(finalId, node(
                finalId,
                new NodeKind.Model(modelRef, "", "", systemPrompt, List.of()),
                MODEL_RETRY_POLICY,
                "agent turn " + maxTurns + ": final answer (no tools)",
                Map.of(
                        "jamjet.agent.loop", "model",
                        "jamjet.agent.turn", String.valueOf(maxTurns),
                        "jamjet.agent.final", "true")));
        edges.add(new EdgeDef(finalId, END, null));

        Gov gov = compileGovernance(agent);

        TimeoutConfig timeouts = new TimeoutConfig(
                null,
                Duration.ofSeconds(agent.timeoutSeconds()),
                Duration.ofSeconds(HEARTBEAT_INTERVAL_SECS),
                null);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("jamjet.agent.id", agent.name());
        labels.put("jamjet.agent.loop", "true");
        labels.put("jamjet.agent.max_turns", String.valueOf(maxTurns));

        String description = blankToNull(agent.instructions()) != null
                ? agent.instructions()
                : "Durable agent: " + agent.name();

        // Build once with a placeholder version, content-hash it, rebuild with the
        // real version so a changed agent (tools / instructions / governance /
        // maxTurns) never reuses an immutably-cached graph (mirrors agent_ir.py).
        WorkflowIr draft = assemble("", agent.name(), description, nodes, edges, timeouts, labels, gov);
        return assemble(contentVersion(draft), agent.name(), description, nodes, edges, timeouts, labels, gov);
    }

    // -- governance (mirrors agent_ir._compile_governance_ir) -------------------

    private record Gov(PolicySetIr policy, TokenBudgetIr tokenBudget, Double costBudgetUsd, DataPolicyIr dataPolicy) {}

    private static Gov compileGovernance(Agent agent) {
        Double costBudgetUsd = null;
        TokenBudgetIr tokenBudget = null;
        Budget budget = agent.budget();
        if (budget != null) {
            if (budget.costUsd() != null) {
                costBudgetUsd = budget.costUsd();
            }
            if (budget.tokens() != null) {
                // total_tokens only: the single most useful combined input+output cap.
                tokenBudget = new TokenBudgetIr(null, null, budget.tokens());
            }
        }
        PolicySetIr policy = compilePolicy(agent);
        DataPolicyIr dataPolicy = agent.pii()
                ? new DataPolicyIr(List.of(), DEFAULT_PII_DETECTORS, "mask", false, true, null)
                : null;
        return new Gov(policy, tokenBudget, costBudgetUsd, dataPolicy);
    }

    /** Mirrors {@code agent_ir._compile_agent_policy_ir}: returns {@code null} when no rules are needed. */
    private static PolicySetIr compilePolicy(Agent agent) {
        boolean hasApproval = agent.approvalAll() || !agent.approvalGlobs().isEmpty();
        boolean hasPolicy = agent.policy() != null;
        if (!hasPolicy && !hasApproval) {
            return null;
        }

        List<String> blocked = new ArrayList<>();
        List<String> require = new ArrayList<>();
        List<String> allowlist = new ArrayList<>();
        if (agent.policy() != null) {
            blocked.addAll(agent.policy().blockedTools());
            require.addAll(agent.policy().requireApprovalFor());
            allowlist.addAll(agent.policy().modelAllowlist());
        }

        if (agent.approvalAll()) {
            require = new ArrayList<>(List.of("*"));  // every tool requires approval
        } else if (!agent.approvalGlobs().isEmpty()) {
            // Union the caller globs with any policy require_approval_for,
            // preserving declaration order and deduplicating.
            LinkedHashSet<String> merged = new LinkedHashSet<>(require);
            merged.addAll(agent.approvalGlobs());
            require = new ArrayList<>(merged);
        }

        return new PolicySetIr(blocked, require, allowlist);
    }

    // -- helpers ----------------------------------------------------------------

    private static NodeDef node(String id, NodeKind kind, String retryPolicy, String description,
                                Map<String, String> labels) {
        return new NodeDef(id, kind, retryPolicy, null, description, labels, null, null);
    }

    private static WorkflowIr assemble(String version, String name, String description,
                                       Map<String, NodeDef> nodes, List<EdgeDef> edges,
                                       TimeoutConfig timeouts, Map<String, String> labels, Gov gov) {
        return new WorkflowIr(
                name,            // workflowId
                version,
                name,            // name
                description,
                "",              // stateSchema
                "__model_0__",   // startNode — always the first Model node (M2)
                nodes,
                edges,
                Map.of(),        // retryPolicies
                timeouts,
                Map.of(),        // models
                Map.of(),        // tools
                Map.of(),        // mcpServers
                Map.of(),        // remoteAgents
                labels,
                gov.policy(),
                gov.tokenBudget(),
                gov.costBudgetUsd(),
                null,            // onBudgetExceeded
                gov.dataPolicy());
    }

    /**
     * The litellm model string, mirroring Python {@code parse_model_ref}: a
     * {@code provider/rest} string lowercases the provider; a bare string passes
     * through unchanged.
     */
    static String litellmModel(String model) {
        String raw = model.strip();
        int slash = raw.indexOf('/');
        if (slash >= 0) {
            String provider = raw.substring(0, slash).strip().toLowerCase(Locale.ROOT);
            String rest = raw.substring(slash + 1);
            return provider + "/" + rest;
        }
        return raw;
    }

    // -- durable-run initial state (mirrors agent_ir.build_initial_state) --------

    /**
     * The execution {@code initial_input} for a compiled agent-loop IR, mirroring the
     * Python {@code build_initial_state}: it seeds the running {@code messages}
     * (system + user prompt) plus the {@code {name: "class#method"}} tool-resolver map
     * into workflow state, so the first Model node reads the messages and every
     * {@code java_fn} tool node finds them. The Rust engine copies this verbatim into
     * {@code current_state} at {@code start_execution} time.
     *
     * <p>The prompt is per-run and private, so it is seeded HERE (not embedded in the
     * workflow definition) — the compiled IR's {@code description} never carries it.
     */
    static Map<String, Object> buildInitialState(Agent agent, String prompt) {
        String system = blankToNull(agent.instructions()) != null
                ? agent.instructions()
                : "You are a helpful assistant.";

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", prompt == null ? "" : prompt));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", messages);
        out.put("tools", toolsMap(agent));
        return out;
    }

    /**
     * {@code {tool_name: "class#method"}} — the Java analog of Python's {@code _tools_map}
     * ({@code {name: "module:qualname"}}). Carried in the seeded state for shape-parity
     * with the Python durable run; the Java {@code JavaToolWorker} resolves tools by name
     * through its own {@link dev.jamjet.agent.tools.ToolRegistry}, so it does not consume
     * this map (the dispatch coordinate is the same {@code class#method}, see
     * {@link RegisteredTool#key()}).
     */
    static Map<String, String> toolsMap(Agent agent) {
        Map<String, String> map = new LinkedHashMap<>();
        for (RegisteredTool t : agent.registry().tools()) {
            map.put(t.name(), t.key());
        }
        return map;
    }

    /**
     * The immutable content-version: {@code "0.1.0+" + sha256(canonical_ir)[:12]}.
     * The hash differs from Python's (the Java IR has {@code java_fn} tool nodes
     * and no python_fn input block), but its purpose — a fresh cache key whenever
     * the agent's compiled content changes — is identical.
     */
    static String contentVersion(WorkflowIr ir) {
        try {
            byte[] canonical = CANON.writeValueAsBytes(ir);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            String hex = HexFormat.of().formatHex(digest);
            return VERSION_BASE + "+" + hex.substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("failed to content-version agent IR", e);
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
