package dev.jamjet.runtime.core.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.QueueType;

import java.time.Instant;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        // Workflow lifecycle
        @JsonSubTypes.Type(value = EventKind.WorkflowStarted.class, name = "workflow_started"),
        @JsonSubTypes.Type(value = EventKind.WorkflowCompleted.class, name = "workflow_completed"),
        @JsonSubTypes.Type(value = EventKind.WorkflowFailed.class, name = "workflow_failed"),
        @JsonSubTypes.Type(value = EventKind.WorkflowCancelled.class, name = "workflow_cancelled"),
        // Node lifecycle
        @JsonSubTypes.Type(value = EventKind.NodeScheduled.class, name = "node_scheduled"),
        @JsonSubTypes.Type(value = EventKind.NodeStarted.class, name = "node_started"),
        @JsonSubTypes.Type(value = EventKind.NodeCompleted.class, name = "node_completed"),
        @JsonSubTypes.Type(value = EventKind.NodeFailed.class, name = "node_failed"),
        @JsonSubTypes.Type(value = EventKind.NodeSkipped.class, name = "node_skipped"),
        @JsonSubTypes.Type(value = EventKind.NodeCancelled.class, name = "node_cancelled"),
        // Retry
        @JsonSubTypes.Type(value = EventKind.RetryScheduled.class, name = "retry_scheduled"),
        // Human approval
        @JsonSubTypes.Type(value = EventKind.InterruptRaised.class, name = "interrupt_raised"),
        @JsonSubTypes.Type(value = EventKind.ApprovalReceived.class, name = "approval_received"),
        // Timers
        @JsonSubTypes.Type(value = EventKind.TimerCreated.class, name = "timer_created"),
        @JsonSubTypes.Type(value = EventKind.TimerFired.class, name = "timer_fired"),
        // External
        @JsonSubTypes.Type(value = EventKind.ExternalEventReceived.class, name = "external_event_received"),
        // Child workflows
        @JsonSubTypes.Type(value = EventKind.ChildWorkflowStarted.class, name = "child_workflow_started"),
        @JsonSubTypes.Type(value = EventKind.ChildWorkflowCompleted.class, name = "child_workflow_completed"),
        @JsonSubTypes.Type(value = EventKind.ChildWorkflowFailed.class, name = "child_workflow_failed"),
        // Budget/autonomy
        @JsonSubTypes.Type(value = EventKind.BudgetExceeded.class, name = "budget_exceeded"),
        @JsonSubTypes.Type(value = EventKind.TokenBudgetExceeded.class, name = "token_budget_exceeded"),
        @JsonSubTypes.Type(value = EventKind.CostBudgetExceeded.class, name = "cost_budget_exceeded"),
        @JsonSubTypes.Type(value = EventKind.AutonomyLimitReached.class, name = "autonomy_limit_reached"),
        @JsonSubTypes.Type(value = EventKind.CircuitBreakerTripped.class, name = "circuit_breaker_tripped"),
        @JsonSubTypes.Type(value = EventKind.EscalationRequired.class, name = "escalation_required"),
        // Policy
        @JsonSubTypes.Type(value = EventKind.PolicyViolation.class, name = "policy_violation"),
        @JsonSubTypes.Type(value = EventKind.ToolApprovalRequired.class, name = "tool_approval_required"),
        // Strategy
        @JsonSubTypes.Type(value = EventKind.StrategyStarted.class, name = "strategy_started"),
        @JsonSubTypes.Type(value = EventKind.PlanGenerated.class, name = "plan_generated"),
        @JsonSubTypes.Type(value = EventKind.IterationStarted.class, name = "iteration_started"),
        @JsonSubTypes.Type(value = EventKind.ToolCalled.class, name = "tool_called"),
        @JsonSubTypes.Type(value = EventKind.CriticVerdict.class, name = "critic_verdict"),
        @JsonSubTypes.Type(value = EventKind.IterationCompleted.class, name = "iteration_completed"),
        @JsonSubTypes.Type(value = EventKind.StrategyLimitHit.class, name = "strategy_limit_hit"),
        @JsonSubTypes.Type(value = EventKind.StrategyCompleted.class, name = "strategy_completed"),
        // Coordinator
        @JsonSubTypes.Type(value = EventKind.CoordinatorDiscovery.class, name = "coordinator_discovery"),
        @JsonSubTypes.Type(value = EventKind.CoordinatorScoring.class, name = "coordinator_scoring"),
        @JsonSubTypes.Type(value = EventKind.CoordinatorDecision.class, name = "coordinator_decision"),
        // Agent-as-Tool
        @JsonSubTypes.Type(value = EventKind.AgentToolInvoked.class, name = "agent_tool_invoked"),
        @JsonSubTypes.Type(value = EventKind.AgentToolProgress.class, name = "agent_tool_progress"),
        @JsonSubTypes.Type(value = EventKind.AgentToolTurn.class, name = "agent_tool_turn"),
        @JsonSubTypes.Type(value = EventKind.AgentToolCompleted.class, name = "agent_tool_completed"),
        @JsonSubTypes.Type(value = EventKind.AgentToolTerminated.class, name = "agent_tool_terminated"),
        @JsonSubTypes.Type(value = EventKind.AgentToolFailed.class, name = "agent_tool_failed")
})
public sealed interface EventKind {

    // ── Workflow lifecycle ──────────────────────────────────────────

    record WorkflowStarted(
            String workflowId,
            String workflowVersion,
            JsonNode initialInput
    ) implements EventKind {}

    record WorkflowCompleted(
            JsonNode finalState
    ) implements EventKind {}

    record WorkflowFailed(
            String error
    ) implements EventKind {}

    record WorkflowCancelled(
            String reason
    ) implements EventKind {}

    // ── Node lifecycle ──────────────────────────────────────────────

    record NodeScheduled(
            String nodeId,
            QueueType queueType
    ) implements EventKind {}

    record NodeStarted(
            String nodeId,
            String workerId,
            int attempt
    ) implements EventKind {}

    record NodeCompleted(
            String nodeId,
            JsonNode output,
            JsonNode statePatch,
            long durationMs,
            String genAiSystem,
            String genAiModel,
            Long inputTokens,
            Long outputTokens,
            String finishReason,
            Double costUsd,
            ProvenanceMetadata provenance
    ) implements EventKind {}

    record NodeFailed(
            String nodeId,
            String error,
            int attempt,
            boolean retryable
    ) implements EventKind {}

    record NodeSkipped(
            String nodeId,
            String reason
    ) implements EventKind {}

    record NodeCancelled(
            String nodeId
    ) implements EventKind {}

    // ── Retry ───────────────────────────────────────────────────────

    record RetryScheduled(
            String nodeId,
            int attempt,
            long delayMs
    ) implements EventKind {}

    // ── Human approval ──────────────────────────────────────────────

    record InterruptRaised(
            String nodeId,
            String reason,
            JsonNode stateForReview
    ) implements EventKind {}

    record ApprovalReceived(
            String nodeId,
            String userId,
            ApprovalDecision decision,
            String comment,
            JsonNode statePatch
    ) implements EventKind {}

    // ── Timers ──────────────────────────────────────────────────────

    record TimerCreated(
            String nodeId,
            Instant fireAt,
            String correlationKey
    ) implements EventKind {}

    record TimerFired(
            String nodeId,
            String correlationKey
    ) implements EventKind {}

    // ── External ────────────────────────────────────────────────────

    record ExternalEventReceived(
            String correlationKey,
            JsonNode payload
    ) implements EventKind {}

    // ── Child workflows ─────────────────────────────────────────────

    record ChildWorkflowStarted(
            String nodeId,
            String childExecutionId,
            String childWorkflowId
    ) implements EventKind {}

    record ChildWorkflowCompleted(
            String nodeId,
            String childExecutionId,
            JsonNode result
    ) implements EventKind {}

    record ChildWorkflowFailed(
            String nodeId,
            String childExecutionId,
            String error
    ) implements EventKind {}

    // ── Budget/autonomy ─────────────────────────────────────────────

    record BudgetExceeded(
            String nodeId,
            String kind,
            long limit,
            long current
    ) implements EventKind {}

    record TokenBudgetExceeded(
            String nodeId,
            String kind,
            long limit,
            long current
    ) implements EventKind {}

    record CostBudgetExceeded(
            String nodeId,
            double limitUsd,
            double currentUsd
    ) implements EventKind {}

    record AutonomyLimitReached(
            String nodeId,
            String agentRef,
            String limitType,
            JsonNode limitValue,
            JsonNode actualValue
    ) implements EventKind {}

    record CircuitBreakerTripped(
            String nodeId,
            String agentRef,
            int consecutiveErrors,
            int threshold
    ) implements EventKind {}

    record EscalationRequired(
            String nodeId,
            String agentRef,
            String reason,
            String escalationTarget
    ) implements EventKind {}

    // ── Policy ──────────────────────────────────────────────────────

    record PolicyViolation(
            String nodeId,
            String rule,
            String decision,
            String policyScope
    ) implements EventKind {}

    record ToolApprovalRequired(
            String nodeId,
            String toolName,
            String approver,
            JsonNode context
    ) implements EventKind {}

    // ── Strategy ────────────────────────────────────────────────────

    record StrategyStarted(
            String strategy,
            JsonNode config
    ) implements EventKind {}

    record PlanGenerated(
            List<String> steps
    ) implements EventKind {
        public PlanGenerated {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    record IterationStarted(
            int iteration
    ) implements EventKind {}

    record ToolCalled(
            String nodeId,
            String tool
    ) implements EventKind {}

    record CriticVerdict(
            String nodeId,
            double score,
            boolean passed,
            String feedback
    ) implements EventKind {}

    record IterationCompleted(
            int iteration,
            Double costDeltaUsd,
            long inputTokens,
            long outputTokens
    ) implements EventKind {}

    record StrategyLimitHit(
            String limitType,
            JsonNode limitValue,
            JsonNode actualValue
    ) implements EventKind {}

    record StrategyCompleted(
            int iterations,
            Double totalCostUsd
    ) implements EventKind {}

    // ── Coordinator ─────────────────────────────────────────────────

    record CoordinatorDiscovery(
            String nodeId,
            List<String> querySkills,
            String queryTrustDomain,
            List<JsonNode> candidates,
            List<JsonNode> filteredOut
    ) implements EventKind {
        public CoordinatorDiscovery {
            querySkills = querySkills == null ? List.of() : List.copyOf(querySkills);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            filteredOut = filteredOut == null ? List.of() : List.copyOf(filteredOut);
        }
    }

    record CoordinatorScoring(
            String nodeId,
            List<JsonNode> rankings,
            double spread,
            JsonNode weights
    ) implements EventKind {
        public CoordinatorScoring {
            rankings = rankings == null ? List.of() : List.copyOf(rankings);
        }
    }

    record CoordinatorDecision(
            String nodeId,
            String selected,
            String method,
            String reasoning,
            double confidence,
            List<JsonNode> rejected,
            JsonNode tiebreakerTokens,
            Double tiebreakerCost
    ) implements EventKind {
        public CoordinatorDecision {
            rejected = rejected == null ? List.of() : List.copyOf(rejected);
        }
    }

    // ── Agent-as-Tool ───────────────────────────────────────────────

    record AgentToolInvoked(
            String nodeId,
            String agentUri,
            String mode,
            String protocol,
            String inputHash
    ) implements EventKind {}

    record AgentToolProgress(
            String nodeId,
            int chunkIndex,
            String partialOutputSummary
    ) implements EventKind {}

    record AgentToolTurn(
            String nodeId,
            int turnNumber,
            String direction,
            String contentSummary,
            int tokens,
            double cost
    ) implements EventKind {}

    record AgentToolCompleted(
            String nodeId,
            JsonNode output,
            JsonNode provenance,
            double totalCost,
            long latencyMs,
            Integer totalTurns
    ) implements EventKind {}

    record AgentToolTerminated(
            String nodeId,
            String reason,
            int chunksReceived,
            JsonNode partialOutput,
            double cost
    ) implements EventKind {}

    record AgentToolFailed(
            String nodeId,
            String failureType,
            String message,
            boolean retryable
    ) implements EventKind {}
}
