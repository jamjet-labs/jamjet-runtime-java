package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.JamjetJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowIrSerializationTest {

    private final ObjectMapper mapper = JamjetJson.shared();

    @Test
    void minimalWorkflowRoundTrip() throws JsonProcessingException {
        var modelNode = new NodeDef(
                "llm",
                new NodeKind.Model("gpt4", "prompt1", null, null),
                null, null, "Call LLM", Map.of(), null, null
        );

        var workflow = new WorkflowIr(
                "wf-1", "1.0", "Test Workflow", "A test",
                null, "llm",
                Map.of("llm", modelNode),
                List.of(),
                Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), null, null, null, null, null
        );

        String json = mapper.writeValueAsString(workflow);

        assertThat(json).contains("\"workflow_id\":\"wf-1\"");
        assertThat(json).contains("\"start_node\":\"llm\"");
        assertThat(json).contains("\"type\":\"model\"");

        WorkflowIr deserialized = mapper.readValue(json, WorkflowIr.class);
        assertThat(deserialized.workflowId()).isEqualTo("wf-1");
        assertThat(deserialized.version()).isEqualTo("1.0");
        assertThat(deserialized.startNode()).isEqualTo("llm");
        assertThat(deserialized.node("llm")).isNotNull();
        assertThat(deserialized.node("llm").kind()).isInstanceOf(NodeKind.Model.class);
    }

    @Test
    void workflowWithEdgesAndPolicy() throws JsonProcessingException {
        var condNode = new NodeDef(
                "check",
                new NodeKind.Condition(List.of(
                        new ConditionalBranch("$.score > 0.8", "good"),
                        new ConditionalBranch(null, "bad")
                )),
                null, null, null, Map.of(), null, null
        );

        var goodNode = new NodeDef(
                "good",
                new NodeKind.Tool("approve_tool", Map.of(), null),
                null, null, null, Map.of(), null, null
        );

        var badNode = new NodeDef(
                "bad",
                new NodeKind.Tool("reject_tool", Map.of(), null),
                null, null, null, Map.of(), null, null
        );

        var policy = new PolicySetIr(
                List.of("dangerous_tool"),
                List.of("delete_action"),
                List.of("gpt-4", "claude-3")
        );

        var workflow = new WorkflowIr(
                "wf-2", "2.0", "Branching Workflow", "Tests edges",
                null, "check",
                Map.of("check", condNode, "good", goodNode, "bad", badNode),
                List.of(
                        new EdgeDef("check", "good", "$.score > 0.8"),
                        new EdgeDef("check", "bad", null)
                ),
                Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), policy, null, null, null, null
        );

        String json = mapper.writeValueAsString(workflow);
        WorkflowIr deserialized = mapper.readValue(json, WorkflowIr.class);

        assertThat(deserialized.edgesFrom("check")).hasSize(2);
        assertThat(deserialized.successors("check")).containsExactlyInAnyOrder("good", "bad");
        assertThat(deserialized.successors("good")).isEmpty();

        assertThat(deserialized.policy()).isNotNull();
        assertThat(deserialized.policy().blockedTools()).containsExactly("dangerous_tool");
        assertThat(deserialized.policy().modelAllowlist()).containsExactly("gpt-4", "claude-3");
    }

    @Test
    void fromJsonStaticFactory() {
        String json = """
                {
                  "workflow_id": "wf-3",
                  "version": "1.0",
                  "name": "FromJson Test",
                  "start_node": "n1",
                  "nodes": {
                    "n1": {
                      "id": "n1",
                      "kind": { "type": "model", "model_ref": "gpt4" }
                    }
                  }
                }
                """;

        WorkflowIr workflow = WorkflowIr.fromJson(json);
        assertThat(workflow.workflowId()).isEqualTo("wf-3");
        assertThat(workflow.node("n1")).isNotNull();
        assertThat(workflow.node("n1").kind()).isInstanceOf(NodeKind.Model.class);
    }

    @Test
    void nullFieldsOmittedFromJson() throws JsonProcessingException {
        var node = new NodeDef(
                "n1",
                new NodeKind.Model("gpt4", null, null, null),
                null, null, null, Map.of(), null, null
        );

        var workflow = new WorkflowIr(
                "wf-4", "1.0", "Null Test", null,
                null, "n1",
                Map.of("n1", node),
                List.of(),
                Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), null, null, null, null, null
        );

        String json = mapper.writeValueAsString(workflow);

        // Null fields should be omitted
        assertThat(json).doesNotContain("\"description\"");
        assertThat(json).doesNotContain("\"state_schema\"");
        assertThat(json).doesNotContain("\"cost_budget_usd\"");
        assertThat(json).doesNotContain("\"prompt_ref\"");
    }
}
