package dev.jamjet.examples.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.executor.ExecutionResult;
import dev.jamjet.runtime.core.executor.NodeExecutor;
import dev.jamjet.runtime.core.state.WorkItem;

/**
 * A stub NodeExecutor that returns a fixed JSON response with 0ms delay.
 * Used to isolate and measure the runtime overhead (state management, queueing,
 * event emission) independently of actual business logic.
 */
public class StubNodeExecutor implements NodeExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String nodeName;

    public StubNodeExecutor(String nodeName) {
        this.nodeName = nodeName;
    }

    @Override
    public ExecutionResult execute(WorkItem item) {
        ObjectNode output = MAPPER.createObjectNode();
        output.put("node", nodeName);
        output.put("status", "ok");

        ObjectNode statePatch = MAPPER.createObjectNode();
        statePatch.put("last_executed", nodeName);

        return ExecutionResult.simple(output, statePatch, 0L);
    }
}
