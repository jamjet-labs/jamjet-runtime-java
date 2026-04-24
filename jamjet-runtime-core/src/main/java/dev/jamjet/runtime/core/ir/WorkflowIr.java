package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.RetryPolicy;
import dev.jamjet.runtime.core.TimeoutConfig;

import java.util.List;
import java.util.Map;

public record WorkflowIr(
        String workflowId,
        String version,
        String name,
        String description,
        String stateSchema,
        String startNode,
        Map<String, NodeDef> nodes,
        List<EdgeDef> edges,
        Map<String, RetryPolicy> retryPolicies,
        TimeoutConfig timeouts,
        Map<String, ModelConfig> models,
        Map<String, ToolConfig> tools,
        Map<String, McpServerConfig> mcpServers,
        Map<String, RemoteAgentConfig> remoteAgents,
        Map<String, String> labels,
        PolicySetIr policy,
        TokenBudgetIr tokenBudget,
        Double costBudgetUsd,
        String onBudgetExceeded,
        DataPolicyIr dataPolicy
) {
    public WorkflowIr {
        nodes = nodes == null ? Map.of() : Map.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        retryPolicies = retryPolicies == null ? Map.of() : Map.copyOf(retryPolicies);
        timeouts = timeouts == null ? TimeoutConfig.defaults() : timeouts;
        models = models == null ? Map.of() : Map.copyOf(models);
        tools = tools == null ? Map.of() : Map.copyOf(tools);
        mcpServers = mcpServers == null ? Map.of() : Map.copyOf(mcpServers);
        remoteAgents = remoteAgents == null ? Map.of() : Map.copyOf(remoteAgents);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    public NodeDef node(String id) {
        return nodes.get(id);
    }

    public List<EdgeDef> edgesFrom(String nodeId) {
        return edges.stream()
                .filter(e -> e.from().equals(nodeId))
                .toList();
    }

    public List<String> successors(String nodeId) {
        return edgesFrom(nodeId).stream()
                .map(EdgeDef::to)
                .toList();
    }

    public static WorkflowIr fromJson(String json) {
        try {
            return JamjetJson.shared().readValue(json, WorkflowIr.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid workflow JSON", e);
        }
    }
}
