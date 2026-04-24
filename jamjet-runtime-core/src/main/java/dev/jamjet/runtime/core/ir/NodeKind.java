package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.jamjet.runtime.core.QueueType;

import java.util.List;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NodeKind.Model.class, name = "model"),
        @JsonSubTypes.Type(value = NodeKind.Tool.class, name = "tool"),
        @JsonSubTypes.Type(value = NodeKind.PythonFn.class, name = "python_fn"),
        @JsonSubTypes.Type(value = NodeKind.Condition.class, name = "condition"),
        @JsonSubTypes.Type(value = NodeKind.Parallel.class, name = "parallel"),
        @JsonSubTypes.Type(value = NodeKind.Join.class, name = "join"),
        @JsonSubTypes.Type(value = NodeKind.HumanApproval.class, name = "human_approval"),
        @JsonSubTypes.Type(value = NodeKind.Wait.class, name = "wait"),
        @JsonSubTypes.Type(value = NodeKind.Subgraph.class, name = "subgraph"),
        @JsonSubTypes.Type(value = NodeKind.MemoryRetrieval.class, name = "memory_retrieval"),
        @JsonSubTypes.Type(value = NodeKind.Policy.class, name = "policy"),
        @JsonSubTypes.Type(value = NodeKind.Finalizer.class, name = "finalizer"),
        @JsonSubTypes.Type(value = NodeKind.Agent.class, name = "agent"),
        @JsonSubTypes.Type(value = NodeKind.McpTool.class, name = "mcp_tool"),
        @JsonSubTypes.Type(value = NodeKind.A2aTask.class, name = "a2a_task"),
        @JsonSubTypes.Type(value = NodeKind.Coordinator.class, name = "coordinator"),
        @JsonSubTypes.Type(value = NodeKind.AgentToolNode.class, name = "agent_tool"),
        @JsonSubTypes.Type(value = NodeKind.Eval.class, name = "eval")
})
public sealed interface NodeKind {

    default QueueType queueType() {
        return switch (this) {
            case Model m -> QueueType.MODEL;
            case Tool t -> QueueType.TOOL;
            case PythonFn p -> QueueType.PYTHON_TOOL;
            case MemoryRetrieval r -> QueueType.RETRIEVAL;
            case Finalizer f -> QueueType.TOOL;
            case McpTool m -> QueueType.TOOL;
            case A2aTask a -> QueueType.TOOL;
            default -> QueueType.GENERAL;
        };
    }

    default boolean isDurable() {
        return !(this instanceof Condition);
    }

    record Model(
            String modelRef,
            String promptRef,
            String outputSchema,
            String systemPrompt
    ) implements NodeKind {}

    record Tool(
            String toolRef,
            Map<String, String> inputMapping,
            String outputSchema
    ) implements NodeKind {
        public Tool {
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        }
    }

    record PythonFn(
            String module,
            String function,
            String outputSchema
    ) implements NodeKind {}

    record Condition(
            List<ConditionalBranch> branches
    ) implements NodeKind {
        public Condition {
            branches = branches == null ? List.of() : List.copyOf(branches);
        }
    }

    record Parallel(
            List<String> branches
    ) implements NodeKind {
        public Parallel {
            branches = branches == null ? List.of() : List.copyOf(branches);
        }
    }

    record Join(
            List<String> waitFor,
            MergeStrategy mergeStrategy
    ) implements NodeKind {
        public Join {
            waitFor = waitFor == null ? List.of() : List.copyOf(waitFor);
        }
    }

    record HumanApproval(
            String description,
            Long timeoutSecs,
            String fallbackNode
    ) implements NodeKind {}

    record Wait(
            WaitCondition condition,
            String correlationKey,
            Long timeoutSecs
    ) implements NodeKind {}

    record Subgraph(
            String workflowRef,
            String workflowVersion,
            Map<String, String> inputMapping,
            Map<String, String> outputMapping
    ) implements NodeKind {
        public Subgraph {
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
            outputMapping = outputMapping == null ? Map.of() : Map.copyOf(outputMapping);
        }
    }

    record MemoryRetrieval(
            String connectorRef,
            String queryExpr,
            String outputSchema
    ) implements NodeKind {}

    record Policy(
            String policyRef,
            ViolationAction onViolation
    ) implements NodeKind {}

    record Finalizer(
            String toolRef,
            FinalizerTrigger runOn
    ) implements NodeKind {}

    record Agent(
            String agentRef,
            Map<String, String> inputMapping,
            String outputSchema
    ) implements NodeKind {
        public Agent {
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        }
    }

    record McpTool(
            String server,
            String tool,
            Map<String, String> inputMapping,
            String outputSchema
    ) implements NodeKind {
        public McpTool {
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        }
    }

    record A2aTask(
            String remoteAgent,
            String skill,
            Map<String, String> inputMapping,
            String outputSchema,
            boolean stream,
            String onInputRequired,
            Long timeoutSecs
    ) implements NodeKind {
        public A2aTask {
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        }
    }

    record Coordinator(
            String task,
            List<String> requiredSkills,
            List<String> preferredSkills,
            String trustDomain,
            CoordinatorBudget budget,
            TiebreakerConfig tiebreaker,
            String strategy,
            DimensionWeights weights,
            Map<String, String> inputMapping,
            String outputKey
    ) implements NodeKind {
        public Coordinator {
            requiredSkills = requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
            preferredSkills = preferredSkills == null ? List.of() : List.copyOf(preferredSkills);
            strategy = strategy == null ? "default" : strategy;
            weights = weights == null ? DimensionWeights.defaults() : weights;
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        }
    }

    record AgentToolNode(
            AgentTarget agent,
            AgentToolMode mode,
            Map<String, String> inputMapping,
            String outputKey,
            Long timeoutMs,
            AgentToolBudget budget
    ) implements NodeKind {
        public AgentToolNode {
            mode = mode == null ? AgentToolMode.SYNC : mode;
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        }
    }

    record Eval(
            List<EvalScorer> scorers,
            EvalOnFail onFail,
            int maxRetries,
            String inputExpr
    ) implements NodeKind {
        public Eval {
            scorers = scorers == null ? List.of() : List.copyOf(scorers);
        }
    }
}
