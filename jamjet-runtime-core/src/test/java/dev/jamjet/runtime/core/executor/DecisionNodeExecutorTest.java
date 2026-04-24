package dev.jamjet.runtime.core.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.WorkItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionNodeExecutorTest {

    private DecisionNodeExecutor executor;
    private final ObjectMapper mapper = JamjetJson.shared();

    @BeforeEach
    void setUp() {
        executor = new DecisionNodeExecutor();
    }

    private WorkItem workItemWithPayload(ObjectNode payload) {
        return new WorkItem(
                UUID.randomUUID(),
                ExecutionId.create(),
                "decide_node",
                "condition",
                payload,
                1,
                3,
                Instant.now(),
                Instant.now().plusSeconds(30),
                "worker-1"
        );
    }

    // ── 1. Selects first matching branch (string equality) ────────────────

    @Test
    void selectsFirstMatchingBranchOnStringEquality() throws NodeExecutionException {
        var branches = mapper.createArrayNode();

        var branchA = mapper.createObjectNode();
        branchA.put("condition", "status == approved");
        branchA.put("target", "node_a");
        branches.add(branchA);

        var branchB = mapper.createObjectNode();
        branchB.put("condition", "default");
        branchB.put("target", "node_b");
        branches.add(branchB);

        var state = mapper.createObjectNode();
        state.put("status", "approved");

        var payload = mapper.createObjectNode();
        payload.set("branches", branches);
        payload.set("state", state);

        ExecutionResult result = executor.execute(workItemWithPayload(payload));

        assertThat(result.output().get("selected_branch").asText()).isEqualTo("node_a");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    // ── 2. Falls back to default when no branch matches ───────────────────

    @Test
    void fallsBackToDefaultWhenNoMatchFound() throws NodeExecutionException {
        var branches = mapper.createArrayNode();

        var branchA = mapper.createObjectNode();
        branchA.put("condition", "status == approved");
        branchA.put("target", "node_a");
        branches.add(branchA);

        var branchDefault = mapper.createObjectNode();
        branchDefault.put("condition", "default");
        branchDefault.put("target", "node_b");
        branches.add(branchDefault);

        var state = mapper.createObjectNode();
        state.put("status", "pending");

        var payload = mapper.createObjectNode();
        payload.set("branches", branches);
        payload.set("state", state);

        ExecutionResult result = executor.execute(workItemWithPayload(payload));

        assertThat(result.output().get("selected_branch").asText()).isEqualTo("node_b");
    }

    // ── 3. Handles numeric > comparison (match) ───────────────────────────

    @Test
    void handlesNumericGreaterThanMatch() throws NodeExecutionException {
        var branches = mapper.createArrayNode();

        var branchHigh = mapper.createObjectNode();
        branchHigh.put("condition", "score > 80");
        branchHigh.put("target", "high_score_node");
        branches.add(branchHigh);

        var branchDefault = mapper.createObjectNode();
        branchDefault.put("condition", "default");
        branchDefault.put("target", "low_score_node");
        branches.add(branchDefault);

        var state = mapper.createObjectNode();
        state.put("score", 95);

        var payload = mapper.createObjectNode();
        payload.set("branches", branches);
        payload.set("state", state);

        ExecutionResult result = executor.execute(workItemWithPayload(payload));

        assertThat(result.output().get("selected_branch").asText()).isEqualTo("high_score_node");
    }

    // ── 4. Handles numeric > comparison (no match, falls to default) ──────

    @Test
    void handlesNumericGreaterThanNoMatchFallsToDefault() throws NodeExecutionException {
        var branches = mapper.createArrayNode();

        var branchHigh = mapper.createObjectNode();
        branchHigh.put("condition", "score > 80");
        branchHigh.put("target", "high_score_node");
        branches.add(branchHigh);

        var branchDefault = mapper.createObjectNode();
        branchDefault.put("condition", "default");
        branchDefault.put("target", "low_score_node");
        branches.add(branchDefault);

        var state = mapper.createObjectNode();
        state.put("score", 50);

        var payload = mapper.createObjectNode();
        payload.set("branches", branches);
        payload.set("state", state);

        ExecutionResult result = executor.execute(workItemWithPayload(payload));

        assertThat(result.output().get("selected_branch").asText()).isEqualTo("low_score_node");
    }

    // ── 5. Error: no branches array in payload ────────────────────────────

    @Test
    void throwsWhenNoBranchesArray() {
        var payload = mapper.createObjectNode();
        payload.set("state", mapper.createObjectNode());

        assertThatThrownBy(() -> executor.execute(workItemWithPayload(payload)))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("branches")
                .matches(e -> !((NodeExecutionException) e).isRetryable());
    }

    // ── 6. Error: no branch matched and no default ────────────────────────

    @Test
    void throwsWhenNoBranchMatchedAndNoDefault() {
        var branches = mapper.createArrayNode();

        var branchA = mapper.createObjectNode();
        branchA.put("condition", "status == approved");
        branchA.put("target", "node_a");
        branches.add(branchA);

        var state = mapper.createObjectNode();
        state.put("status", "rejected");

        var payload = mapper.createObjectNode();
        payload.set("branches", branches);
        payload.set("state", state);

        assertThatThrownBy(() -> executor.execute(workItemWithPayload(payload)))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("No branch matched")
                .matches(e -> !((NodeExecutionException) e).isRetryable());
    }
}
