package dev.jamjet.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.ir.PolicySetIr;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GOLDEN-FILE PARITY: the Java {@code Agent} builder must emit the SAME agent-loop
 * graph the Python ADK emits, so both run on the identical Rust engine.
 *
 * <p>The golden {@code golden/agent_loop_ir.python.json} is the output of the
 * canonical Python {@code jamjet.compiler.agent_ir.compile_agent_to_ir} for a small
 * fixture agent (model + two @tool functions + instructions + inline policy +
 * approval globs + token/cost budget + pii) at {@code max_turns=2}. This test builds
 * the structurally identical Java {@link Agent} and asserts every load-bearing field
 * matches: node ids, edges, the gate condition, the Model nodes' tool schemas,
 * governance (policy / budget / data_policy), turn count, and {@code start_node}.
 *
 * <h2>Documented, expected differences (asserted explicitly below)</h2>
 * <ul>
 *   <li><b>Tool node kind</b>: Python emits {@code python_fn{module,function,input}}
 *       pointing at {@code jamjet.agents.tool_runtime:dispatch_tool_calls}; Java emits
 *       {@code java_fn{class_name,method}} pointing at the fixed Java dispatcher. The
 *       Rust {@code JavaFn} struct has no {@code input} field (the scheduler enriches
 *       the payload from state), so the Java tool node carries no {@code input} block.</li>
 *   <li><b>Version</b>: both are {@code "0.1.0+"+sha256(content)[:12]}, but the hash
 *       differs because the Java IR's content differs (java_fn vs python_fn).</li>
 *   <li><b>Per-node {@code description}</b>: cosmetic human text the engine ignores;
 *       Java uses house punctuation. Excluded from the structural comparison.</li>
 *   <li><b>Gate {@code expression}</b>: Python adds a redundant {@code expression}
 *       alongside {@code branches}; Java emits only {@code branches} (the engine reads
 *       branches and ignores expression).</li>
 *   <li><b>Explicit nulls</b>: the snake_case {@code JamjetJson} mapper omits null
 *       fields (NON_NULL) where Python writes {@code "condition": null} etc.; both
 *       trees are null-stripped before comparison.</li>
 * </ul>
 */
class AgentIrParityTest {

    private static final ObjectMapper M = JamjetJson.shared();

    private static final List<String> EXPECTED_NODE_IDS = List.of(
            "__model_0__", "__tool_gate_0__", "__tools_0__",
            "__model_1__", "__tool_gate_1__", "__tools_1__",
            "__model_2__");

    private static JsonNode py;   // the Python golden IR
    private static JsonNode jv;   // the Java-emitted IR

    @BeforeAll
    static void compileBoth() throws Exception {
        py = loadGolden();
        jv = M.readTree(M.writeValueAsBytes(buildJavaAgent().compileToIr(2)));
    }

    /** The structurally identical Java agent matching the Python golden fixture. */
    private static Agent buildJavaAgent() {
        return Agent.builder("research_agent")
                .model("anthropic/claude-sonnet-4-6")
                .instructions("You are a helpful research assistant.")
                .tools(new TestTools.WebSearchTool(), new TestTools.MathTool())
                .policy(new PolicySetIr(
                        List.of("delete_db"),                       // blocked_tools
                        List.of(),                                  // require_approval_for (from approval globs)
                        List.of("anthropic/claude-sonnet-4-6")))    // model_allowlist
                .approvalRequired(List.of("delete_*"))
                .budget(new Budget(100_000, 2.5))
                .pii(true)
                .build();
    }

    // -- structural parity (must match) -----------------------------------------

    @Test
    void sameTopLevelIdentityAndStartNode() {
        for (String f : List.of("workflow_id", "name", "description", "state_schema", "start_node")) {
            assertThat(jv.get(f)).as(f).isEqualTo(py.get(f));
        }
        // M2: the loop ALWAYS starts on the first Model node, never a tool node.
        assertThat(jv.get("start_node").asText()).isEqualTo("__model_0__");
    }

    @Test
    void sameNodeIdSet() {
        Set<String> pyIds = nodeIds(py);
        Set<String> jvIds = nodeIds(jv);
        assertThat(jvIds).isEqualTo(pyIds);
        assertThat(jvIds).containsExactlyInAnyOrderElementsOf(EXPECTED_NODE_IDS);
    }

    @Test
    void sameTurnCount() {
        // 2 model->tools turns + 1 final model node; max_turns label = "2".
        assertThat(jv.get("labels").get("jamjet.agent.max_turns").asText()).isEqualTo("2");
        assertThat(jv.get("labels").get("jamjet.agent.max_turns").asText())
                .isEqualTo(py.get("labels").get("jamjet.agent.max_turns").asText());
        long jvToolNodes = nodeIds(jv).stream().filter(id -> id.startsWith("__tools_")).count();
        assertThat(jvToolNodes).isEqualTo(2);
    }

    @Test
    void sameEdgesGraph() {
        assertThat(edgeSet(jv)).isEqualTo(edgeSet(py));
        // 9 edges: per turn {model->gate, gate->tools, gate->end, tools->model}, + final model->end.
        assertThat(jv.get("edges")).hasSize(9);
    }

    @Test
    void sameModelNodesIncludingToolSchemas() {
        // The Model node kinds must be byte-identical: model_ref, prompt_ref,
        // output_schema, system_prompt, AND the OpenAI tool schemas offered to the
        // model. This is what makes "the same graph the engine runs" true.
        for (String id : List.of("__model_0__", "__model_1__", "__model_2__")) {
            JsonNode pk = canonicalize(py.get("nodes").get(id).get("kind"));
            JsonNode jk = canonicalize(jv.get("nodes").get(id).get("kind"));
            assertThat(jk).as("model kind %s", id).isEqualTo(pk);
        }
        // The per-turn models carry both tools; the final model carries none.
        assertThat(jv.get("nodes").get("__model_0__").get("kind").get("tools")).hasSize(2);
        assertThat(jv.get("nodes").get("__model_2__").get("kind").get("tools")).hasSize(0);
        assertThat(jv.get("nodes").get("__model_0__").get("kind").get("model_ref").asText())
                .isEqualTo("anthropic/claude-sonnet-4-6");
    }

    @Test
    void sameGateBranches() {
        for (String id : List.of("__tool_gate_0__", "__tool_gate_1__")) {
            JsonNode pb = canonicalize(py.get("nodes").get(id).get("kind").get("branches"));
            JsonNode jb = canonicalize(jv.get("nodes").get(id).get("kind").get("branches"));
            assertThat(jb).as("gate branches %s", id).isEqualTo(pb);
        }
        // The condition the gate branches on.
        assertThat(jv.get("nodes").get("__tool_gate_0__").get("kind")
                .get("branches").get(0).get("condition").asText())
                .isEqualTo("state.last_model_finish_reason == \"tool_calls\"");
    }

    @Test
    void sameGovernanceFields() {
        for (String f : List.of("policy", "cost_budget_usd", "token_budget", "data_policy")) {
            assertThat(canonicalize(jv.get(f))).as(f).isEqualTo(canonicalize(py.get(f)));
        }
        // Spot-check the merged require_approval_for (the ["delete_*"] approval glob).
        assertThat(jv.get("policy").get("require_approval_for").get(0).asText()).isEqualTo("delete_*");
        assertThat(jv.get("token_budget").get("total_tokens").asInt()).isEqualTo(100_000);
        assertThat(jv.get("cost_budget_usd").asDouble()).isEqualTo(2.5);
        assertThat(jv.get("data_policy").get("pii_detectors")).hasSize(5);
    }

    @Test
    void sameTimeoutsLabelsAndEmptyMaps() {
        assertThat(canonicalize(jv.get("timeouts"))).isEqualTo(canonicalize(py.get("timeouts")));
        assertThat(canonicalize(jv.get("labels"))).isEqualTo(canonicalize(py.get("labels")));
        for (String f : List.of("retry_policies", "models", "tools", "mcp_servers", "remote_agents")) {
            assertThat(jv.get(f)).as(f).isEqualTo(py.get(f));
        }
    }

    // -- documented differences (asserted explicitly) ---------------------------

    @Test
    void toolNodesAreJavaFnNotPythonFn() {
        for (String id : List.of("__tools_0__", "__tools_1__")) {
            JsonNode pyTool = py.get("nodes").get(id);
            JsonNode jvTool = jv.get("nodes").get(id);

            // THE key difference: python_fn -> java_fn, with the Java dispatch ref.
            assertThat(pyTool.get("kind").get("type").asText()).isEqualTo("python_fn");
            assertThat(jvTool.get("kind").get("type").asText()).isEqualTo("java_fn");

            assertThat(jvTool.get("kind").get("class_name").asText())
                    .isEqualTo("dev.jamjet.agent.tools.ToolDispatcher");
            assertThat(jvTool.get("kind").get("method").asText()).isEqualTo("dispatchToolCalls");
            assertThat(jvTool.get("kind").get("output_schema").asText()).isEmpty();

            // The Rust JavaFn struct has no `input` field; the scheduler enriches the
            // payload from state. Python's python_fn carries a redundant `input` block.
            assertThat(jvTool.get("kind").has("input")).isFalse();
            assertThat(pyTool.get("kind").has("input")).isTrue();

            // Everything else about the tool node matches: id, retry policy, labels,
            // and the surrounding edges (verified in sameEdgesGraph).
            assertThat(jvTool.get("id").asText()).isEqualTo(pyTool.get("id").asText());
            assertThat(jvTool.get("retry_policy").asText())
                    .isEqualTo(pyTool.get("retry_policy").asText())
                    .isEqualTo("no_retry");
            assertThat(canonicalize(jvTool.get("labels"))).isEqualTo(canonicalize(pyTool.get("labels")));
        }
    }

    @Test
    void versionDiffersButBothAreContentHashed() {
        assertThat(py.get("version").asText()).startsWith("0.1.0+");
        assertThat(jv.get("version").asText()).startsWith("0.1.0+");
        // The java_fn content yields a different hash than the python_fn content.
        assertThat(jv.get("version").asText()).isNotEqualTo(py.get("version").asText());
    }

    // -- backstop: deep-equal of the whole graph after normalization ------------

    @Test
    void deepEqualAfterNormalization() {
        // The strongest statement: once the documented differences are normalized
        // away (version, per-node description, gate expression, the tool-node kind),
        // the ENTIRE Java IR tree deep-equals the Python IR tree.
        assertThat(normalize(jv)).isEqualTo(normalize(py));
    }

    // -- helpers ----------------------------------------------------------------

    private static JsonNode loadGolden() throws Exception {
        try (InputStream in = AgentIrParityTest.class.getResourceAsStream("/golden/agent_loop_ir.python.json")) {
            assertThat(in).as("golden resource present").isNotNull();
            return M.readTree(in);
        }
    }

    private static Set<String> nodeIds(JsonNode ir) {
        Set<String> ids = new TreeSet<>();
        ir.get("nodes").fieldNames().forEachRemaining(ids::add);
        return ids;
    }

    private static Set<JsonNode> edgeSet(JsonNode ir) {
        Set<JsonNode> edges = new HashSet<>();
        canonicalize(ir.get("edges")).forEach(edges::add);
        return edges;
    }

    /** Recursively strip null-valued object fields (harmonize NON_NULL vs explicit null). */
    private static JsonNode canonicalize(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode out = M.createObjectNode();
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    out.set(e.getKey(), canonicalize(e.getValue()));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = M.createArrayNode();
            node.forEach(c -> out.add(canonicalize(c)));
            return out;
        }
        return node;
    }

    /**
     * Null-strip the whole IR and remove the documented-divergent fields so the
     * remaining graph (identity / model nodes incl. tool schemas / gate branches /
     * governance / edges / timeouts / labels) can be deep-compared. The tool-node
     * {@code kind} legitimately differs (java_fn vs python_fn) and is masked with a
     * sentinel so the rest of each tool node (id / retry_policy / labels) is still
     * compared.
     */
    private static JsonNode normalize(JsonNode root) {
        ObjectNode r = (ObjectNode) canonicalize(root);
        r.remove("version");
        ObjectNode nodes = (ObjectNode) r.get("nodes");
        nodes.fields().forEachRemaining(e -> {
            ObjectNode n = (ObjectNode) e.getValue();
            n.remove("description");  // cosmetic; Java uses house punctuation
            ObjectNode kind = (ObjectNode) n.get("kind");
            if ("condition".equals(kind.path("type").asText())) {
                kind.remove("expression");  // Python-only redundant metadata
            }
            if (e.getKey().startsWith("__tools_")) {
                n.set("kind", M.createObjectNode().put("type", "__tool_dispatch__"));
            }
        });
        return r;
    }
}
