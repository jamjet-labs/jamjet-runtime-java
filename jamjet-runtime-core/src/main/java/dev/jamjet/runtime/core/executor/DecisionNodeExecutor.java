package dev.jamjet.runtime.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.WorkItem;

/**
 * Executes {@code condition} nodes by evaluating ordered branch expressions
 * against the workflow state and returning the first matching branch target.
 *
 * <p>Supported condition expressions:
 * <ul>
 *   <li>{@code field == value} — string equality</li>
 *   <li>{@code field > number} — numeric greater-than</li>
 *   <li>{@code field < number} — numeric less-than</li>
 *   <li>{@code default} — always matches; used as a fallback</li>
 * </ul>
 *
 * <p>Expected payload shape:
 * <pre>{@code
 * {
 *   "branches": [
 *     {"condition": "status == approved", "target": "node_a"},
 *     {"condition": "default",            "target": "node_b"}
 *   ],
 *   "state": {"status": "approved"}
 * }
 * }</pre>
 *
 * <p>Output: {@code {"selected_branch": "<target>"}}
 *
 * <p>This class is thread-safe; no mutable state is held.
 */
public class DecisionNodeExecutor implements NodeExecutor {

    @Override
    public ExecutionResult execute(WorkItem item) throws NodeExecutionException {
        long start = System.currentTimeMillis();

        JsonNode payload = item.payload();

        // Validate branches array
        JsonNode branchesNode = payload.get("branches");
        if (branchesNode == null || !branchesNode.isArray() || branchesNode.isEmpty()) {
            throw new NodeExecutionException(
                    "Payload must contain a non-empty 'branches' array", false);
        }

        JsonNode state = payload.path("state");

        // Evaluate branches in order
        String selectedTarget = null;
        String defaultTarget = null;

        for (JsonNode branch : branchesNode) {
            String condition = branch.path("condition").asText();
            String target = branch.path("target").asText();

            if ("default".equalsIgnoreCase(condition.trim())) {
                // Record default but keep scanning — a non-default match takes priority
                if (defaultTarget == null) {
                    defaultTarget = target;
                }
                continue;
            }

            if (evaluate(condition, state)) {
                selectedTarget = target;
                break;
            }
        }

        if (selectedTarget == null) {
            selectedTarget = defaultTarget;
        }

        if (selectedTarget == null) {
            throw new NodeExecutionException(
                    "No branch matched and no default branch defined", false);
        }

        var output = JamjetJson.shared().createObjectNode();
        output.put("selected_branch", selectedTarget);

        long durationMs = System.currentTimeMillis() - start;
        return ExecutionResult.simple(output, JamjetJson.shared().createObjectNode(), durationMs);
    }

    /**
     * Evaluates a single condition expression against the provided state.
     *
     * @param condition the expression string, e.g. {@code "status == approved"} or
     *                  {@code "score > 80"}
     * @param state     the workflow state JSON object
     * @return {@code true} if the condition holds
     */
    private boolean evaluate(String condition, JsonNode state) {
        String trimmed = condition.trim();

        if (trimmed.contains("==")) {
            String[] parts = trimmed.split("==", 2);
            String field = parts[0].trim();
            String expected = parts[1].trim();
            JsonNode fieldNode = state.path(field);
            return !fieldNode.isMissingNode() && expected.equals(fieldNode.asText().trim());
        }

        if (trimmed.contains(">")) {
            String[] parts = trimmed.split(">", 2);
            String field = parts[0].trim();
            String numberStr = parts[1].trim();
            JsonNode fieldNode = state.path(field);
            if (fieldNode.isMissingNode() || !fieldNode.isNumber()) {
                return false;
            }
            try {
                double threshold = Double.parseDouble(numberStr);
                return fieldNode.asDouble() > threshold;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        if (trimmed.contains("<")) {
            String[] parts = trimmed.split("<", 2);
            String field = parts[0].trim();
            String numberStr = parts[1].trim();
            JsonNode fieldNode = state.path(field);
            if (fieldNode.isMissingNode() || !fieldNode.isNumber()) {
                return false;
            }
            try {
                double threshold = Double.parseDouble(numberStr);
                return fieldNode.asDouble() < threshold;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // Unknown expression — does not match
        return false;
    }
}
