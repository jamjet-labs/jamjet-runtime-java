package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.QueueType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NodeKindSerializationTest {

    private final ObjectMapper mapper = JamjetJson.shared();

    @Test
    void modelNodeRoundTrip() throws JsonProcessingException {
        NodeKind original = new NodeKind.Model("gpt4", "summarize", "{}", "You are helpful");
        String json = mapper.writeValueAsString(original);

        assertThat(json).contains("\"type\":\"model\"");
        assertThat(json).contains("\"model_ref\":\"gpt4\"");
        assertThat(json).contains("\"prompt_ref\":\"summarize\"");
        assertThat(json).contains("\"system_prompt\":\"You are helpful\"");

        NodeKind deserialized = mapper.readValue(json, NodeKind.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void toolNodeRoundTrip() throws JsonProcessingException {
        NodeKind original = new NodeKind.Tool("search_tool", Map.of("query", "$.input.query"), "{}");
        String json = mapper.writeValueAsString(original);

        assertThat(json).contains("\"type\":\"tool\"");
        assertThat(json).contains("\"tool_ref\":\"search_tool\"");
        assertThat(json).contains("\"input_mapping\"");

        NodeKind deserialized = mapper.readValue(json, NodeKind.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void conditionNodeRoundTrip() throws JsonProcessingException {
        NodeKind original = new NodeKind.Condition(List.of(
                new ConditionalBranch("$.score > 0.8", "good_path"),
                new ConditionalBranch(null, "fallback_path")
        ));
        String json = mapper.writeValueAsString(original);

        assertThat(json).contains("\"type\":\"condition\"");
        assertThat(json).contains("\"branches\"");

        NodeKind deserialized = mapper.readValue(json, NodeKind.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void evalNodeRoundTrip() throws JsonProcessingException {
        NodeKind original = new NodeKind.Eval(
                List.of(
                        new EvalScorer.LlmJudge("gpt-4", "Rate quality 1-5", 3.0),
                        new EvalScorer.Latency(5000)
                ),
                EvalOnFail.RETRY_WITH_FEEDBACK,
                3,
                "$.output"
        );
        String json = mapper.writeValueAsString(original);

        assertThat(json).contains("\"type\":\"eval\"");
        assertThat(json).contains("\"on_fail\":\"retry_with_feedback\"");
        assertThat(json).contains("\"max_retries\":3");

        NodeKind deserialized = mapper.readValue(json, NodeKind.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void queueTypeMapping() {
        assertThat(new NodeKind.Model("m", null, null, null).queueType()).isEqualTo(QueueType.MODEL);
        assertThat(new NodeKind.Tool("t", null, null).queueType()).isEqualTo(QueueType.TOOL);
        assertThat(new NodeKind.PythonFn("m", "f", null).queueType()).isEqualTo(QueueType.PYTHON_TOOL);
        assertThat(new NodeKind.MemoryRetrieval("c", "q", null).queueType()).isEqualTo(QueueType.RETRIEVAL);
        assertThat(new NodeKind.Finalizer("t", FinalizerTrigger.ALWAYS).queueType()).isEqualTo(QueueType.TOOL);
        assertThat(new NodeKind.McpTool("s", "t", null, null).queueType()).isEqualTo(QueueType.TOOL);
        assertThat(new NodeKind.A2aTask("r", "s", null, null, false, null, null).queueType()).isEqualTo(QueueType.TOOL);
        assertThat(new NodeKind.Condition(List.of()).queueType()).isEqualTo(QueueType.GENERAL);
        assertThat(new NodeKind.Parallel(List.of()).queueType()).isEqualTo(QueueType.GENERAL);
        assertThat(new NodeKind.Join(List.of(), null).queueType()).isEqualTo(QueueType.GENERAL);
        assertThat(new NodeKind.Coordinator("t", null, null, null, null, null, null, null, null, null).queueType()).isEqualTo(QueueType.GENERAL);
    }

    @Test
    void isDurableMapping() {
        assertThat(new NodeKind.Model("m", null, null, null).isDurable()).isTrue();
        assertThat(new NodeKind.Tool("t", null, null).isDurable()).isTrue();
        assertThat(new NodeKind.Condition(List.of()).isDurable()).isFalse();
        assertThat(new NodeKind.HumanApproval("desc", null, null).isDurable()).isTrue();
        assertThat(new NodeKind.Eval(List.of(), EvalOnFail.HALT, 0, null).isDurable()).isTrue();
    }

    @Test
    void authConfigRoundTrip() throws JsonProcessingException {
        AuthConfig bearer = new AuthConfig.Bearer("MY_TOKEN_ENV");
        String json = mapper.writeValueAsString(bearer);
        assertThat(json).contains("\"type\":\"bearer\"");
        assertThat(json).contains("\"token_env\":\"MY_TOKEN_ENV\"");
        AuthConfig deserialized = mapper.readValue(json, AuthConfig.class);
        assertThat(deserialized).isEqualTo(bearer);

        AuthConfig apiKey = new AuthConfig.ApiKey("X-Api-Key", "API_KEY_ENV");
        json = mapper.writeValueAsString(apiKey);
        assertThat(json).contains("\"type\":\"api_key\"");
        deserialized = mapper.readValue(json, AuthConfig.class);
        assertThat(deserialized).isEqualTo(apiKey);
    }

    @Test
    void mergeStrategyRoundTrip() throws JsonProcessingException {
        MergeStrategy collect = new MergeStrategy.Collect();
        String json = mapper.writeValueAsString(collect);
        assertThat(json).contains("\"type\":\"collect\"");
        assertThat(mapper.readValue(json, MergeStrategy.class)).isEqualTo(collect);

        MergeStrategy custom = new MergeStrategy.Custom("my_func");
        json = mapper.writeValueAsString(custom);
        assertThat(json).contains("\"type\":\"custom\"");
        assertThat(json).contains("\"function_ref\":\"my_func\"");
        assertThat(mapper.readValue(json, MergeStrategy.class)).isEqualTo(custom);
    }

    @Test
    void evalScorerRoundTrip() throws JsonProcessingException {
        EvalScorer latency = new EvalScorer.Latency(5000);
        String json = mapper.writeValueAsString(latency);
        assertThat(json).contains("\"type\":\"latency\"");
        assertThat(json).contains("\"threshold_ms\":5000");
        assertThat(mapper.readValue(json, EvalScorer.class)).isEqualTo(latency);

        EvalScorer assertion = new EvalScorer.Assertion(List.of("len(output) > 0", "output.score >= 3"));
        json = mapper.writeValueAsString(assertion);
        assertThat(json).contains("\"type\":\"assertion\"");
        assertThat(mapper.readValue(json, EvalScorer.class)).isEqualTo(assertion);
    }

    @Test
    void agentToolNodeDefaultMode() throws JsonProcessingException {
        NodeKind node = new NodeKind.AgentToolNode(
                new AgentTarget("http://agent", "agent-1"),
                null, null, "result", null, null
        );
        assertThat(((NodeKind.AgentToolNode) node).mode()).isEqualTo(AgentToolMode.SYNC);

        String json = mapper.writeValueAsString(node);
        assertThat(json).contains("\"type\":\"agent_tool\"");
        NodeKind deserialized = mapper.readValue(json, NodeKind.class);
        assertThat(deserialized).isInstanceOf(NodeKind.AgentToolNode.class);
        assertThat(((NodeKind.AgentToolNode) deserialized).mode()).isEqualTo(AgentToolMode.SYNC);
    }
}
