package dev.jamjet.agent;

import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.ir.NodeKind;
import dev.jamjet.runtime.core.ir.PolicySetIr;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural unit tests for {@link AgentIrCompiler} that do not require the Python
 * golden: the static unroll shape, the M2 start-node invariant, the JavaFn tool
 * nodes, governance omission, model-ref parsing, and content-version behaviour.
 */
class AgentIrCompilerTest {

    private static Agent agent(int timeout, String instructions) {
        return Agent.builder("a1")
                .model("anthropic/claude-sonnet-4-6")
                .instructions(instructions)
                .tools(new TestTools.WebSearchTool())
                .timeoutSeconds(timeout)
                .build();
    }

    @Test
    void rejectsNonPositiveMaxTurns() {
        assertThatThrownBy(() -> agent(300, "hi").compileToIr(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTurns");
    }

    @Test
    void startNodeIsAlwaysFirstModelNode_M2() {
        WorkflowIr ir = agent(300, "hi").compileToIr(3);
        assertThat(ir.startNode()).isEqualTo("__model_0__");
        // The start node must be a Model node, never a tool node.
        assertThat(ir.node(ir.startNode()).kind()).isInstanceOf(NodeKind.Model.class);
    }

    @Test
    void unrollEmitsThreeNodesPerTurnPlusFinalModel() {
        WorkflowIr ir = agent(300, "hi").compileToIr(4);
        // 4 turns * (model + gate + tools) + 1 final model = 13 nodes.
        assertThat(ir.nodes()).hasSize(4 * 3 + 1);
        assertThat(ir.node("__model_4__")).isNotNull();          // final model
        assertThat(ir.node("__tools_3__").kind()).isInstanceOf(NodeKind.JavaFn.class);
        assertThat(ir.node("__tools_4__")).isNull();             // no tools after the final model
    }

    @Test
    void toolNodesAreJavaFnPointingAtTheFixedDispatcher() {
        WorkflowIr ir = agent(300, "hi").compileToIr(2);
        NodeKind.JavaFn tools = (NodeKind.JavaFn) ir.node("__tools_0__").kind();
        assertThat(tools.className()).isEqualTo("dev.jamjet.agent.tools.ToolDispatcher");
        assertThat(tools.method()).isEqualTo("dispatchToolCalls");
        assertThat(tools.outputSchema()).isEmpty();
        assertThat(tools.queueType().getValue()).isEqualTo("java_tool");
        assertThat(ir.node("__tools_0__").retryPolicy()).isEqualTo("no_retry");
    }

    @Test
    void perTurnModelsCarryToolSchemasFinalModelDoesNot() {
        WorkflowIr ir = agent(300, "hi").compileToIr(2);
        NodeKind.Model turn0 = (NodeKind.Model) ir.node("__model_0__").kind();
        NodeKind.Model finalModel = (NodeKind.Model) ir.node("__model_2__").kind();
        assertThat(turn0.tools()).hasSize(1);
        assertThat(finalModel.tools()).isEmpty();
        assertThat(ir.node("__model_0__").retryPolicy()).isEqualTo("llm_default");
    }

    @Test
    void blankInstructionsYieldNullSystemPromptAndDefaultDescription() {
        WorkflowIr ir = Agent.builder("a1").model("gpt-4o").tools().build().compileToIr(1);
        NodeKind.Model m = (NodeKind.Model) ir.node("__model_0__").kind();
        assertThat(m.systemPrompt()).isNull();
        assertThat(ir.description()).isEqualTo("Durable agent: a1");
    }

    @Test
    void litellmModelMirrorsPythonParseModelRef() {
        // provider/rest lowercases the provider; bare strings pass through.
        assertThat(AgentIrCompiler.litellmModel("anthropic/claude-sonnet-4-6"))
                .isEqualTo("anthropic/claude-sonnet-4-6");
        assertThat(AgentIrCompiler.litellmModel("Anthropic/Claude-Opus")).isEqualTo("anthropic/Claude-Opus");
        assertThat(AgentIrCompiler.litellmModel("gpt-4o")).isEqualTo("gpt-4o");
        assertThat(AgentIrCompiler.litellmModel("  openai/gpt-4o  ")).isEqualTo("openai/gpt-4o");
    }

    @Test
    void defaultMaxTurnsIsEight() {
        WorkflowIr ir = agent(300, "hi").compileToIr();
        assertThat(ir.node("__model_8__")).isNotNull();   // final answer node at maxTurns=8
        assertThat(ir.node("__tools_7__")).isNotNull();
        assertThat(ir.node("__tools_8__")).isNull();
    }

    @Test
    void governanceFieldsOmittedWhenUnset() throws Exception {
        // No policy, no budget, pii off -> none of the governance IR fields emit.
        Agent bare = Agent.builder("a1").model("gpt-4o").tools(new TestTools.WebSearchTool())
                .pii(false).build();
        WorkflowIr ir = bare.compileToIr(1);
        assertThat(ir.policy()).isNull();
        assertThat(ir.tokenBudget()).isNull();
        assertThat(ir.costBudgetUsd()).isNull();
        assertThat(ir.dataPolicy()).isNull();

        String json = JamjetJson.shared().writeValueAsString(ir);
        assertThat(json).doesNotContain("\"policy\"");
        assertThat(json).doesNotContain("\"cost_budget_usd\"");
        assertThat(json).doesNotContain("\"token_budget\"");
        assertThat(json).doesNotContain("\"data_policy\"");
    }

    @Test
    void approvalAllSetsWildcardRequireApproval() {
        Agent a = Agent.builder("a1").model("gpt-4o").tools(new TestTools.WebSearchTool())
                .approvalRequired(true).build();
        PolicySetIr policy = a.compileToIr(1).policy();
        assertThat(policy).isNotNull();
        assertThat(policy.requireApprovalFor()).containsExactly("*");
    }

    @Test
    void approvalGlobsUnionWithInlinePolicyPreservingOrder() {
        Agent a = Agent.builder("a1").model("gpt-4o").tools(new TestTools.WebSearchTool())
                .policy(new PolicySetIr(List.of(), List.of("wire_*"), List.of()))
                .approvalRequired(List.of("delete_*", "wire_*"))   // wire_* is a dup
                .build();
        PolicySetIr policy = a.compileToIr(1).policy();
        // Union preserves the policy's order first, then new globs; dedups wire_*.
        assertThat(policy.requireApprovalFor()).containsExactly("wire_*", "delete_*");
    }

    @Test
    void emittedIrRoundTripsThroughTypedPort() throws Exception {
        // The emitted agent IR must survive a serialize -> deserialize through the
        // same typed WorkflowIr port the engine uses, with JavaFn tool nodes and
        // tool-carrying Model nodes intact.
        WorkflowIr ir = agent(300, "hi").compileToIr(2);
        String json = JamjetJson.shared().writeValueAsString(ir);
        WorkflowIr back = WorkflowIr.fromJson(json);

        assertThat(back.startNode()).isEqualTo("__model_0__");
        assertThat(back.nodes()).hasSize(7);
        assertThat(back.node("__tools_0__").kind()).isInstanceOf(NodeKind.JavaFn.class);
        NodeKind.Model m = (NodeKind.Model) back.node("__model_0__").kind();
        assertThat(m.tools()).hasSize(1);
    }

    @Test
    void contentVersionIsDeterministicAndContentSensitive() {
        String v1 = agent(300, "hello").compileToIr(2).version();
        String v1Again = agent(300, "hello").compileToIr(2).version();
        String vDifferentInstructions = agent(300, "different").compileToIr(2).version();
        String vDifferentTurns = agent(300, "hello").compileToIr(3).version();

        assertThat(v1).startsWith("0.1.0+").isEqualTo(v1Again);   // same agent -> same version
        assertThat(vDifferentInstructions).isNotEqualTo(v1);       // changed content -> new key
        assertThat(vDifferentTurns).isNotEqualTo(v1);              // changed unroll -> new key
    }
}
