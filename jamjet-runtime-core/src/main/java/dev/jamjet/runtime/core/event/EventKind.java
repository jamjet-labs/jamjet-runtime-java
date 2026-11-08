package dev.jamjet.runtime.core.event;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.QueueType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every event kind in a JamJet event log, mirroring {@code EventKind} in the Rust runtime
 * ({@code runtime/state/src/event.rs}): same {@code type} tags, same snake_case fields.
 *
 * <p>A {@code type} this build does not know reads as {@link Unknown} instead of failing the
 * whole log, as Rust's {@code #[serde(other)] Unknown} does. It is not ignored where it could
 * matter: it may be a revocation or a denial, so the scheduler does not advance a run whose
 * log holds one (see {@link RunStalled}).</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = EventKind.Unknown.class, visible = true)
@JsonIgnoreProperties({"type"})
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
        @JsonSubTypes.Type(value = EventKind.NodeParked.class, name = "node_parked"),
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
        @JsonSubTypes.Type(value = EventKind.AgentStepAdmitted.class, name = "agent_step_admitted"),
        @JsonSubTypes.Type(value = EventKind.EscalationRequired.class, name = "escalation_required"),
        // Policy
        @JsonSubTypes.Type(value = EventKind.PolicyViolation.class, name = "policy_violation"),
        @JsonSubTypes.Type(value = EventKind.ToolApprovalRequired.class, name = "tool_approval_required"),
        @JsonSubTypes.Type(value = EventKind.ApprovalExpired.class, name = "approval_expired"),
        @JsonSubTypes.Type(value = EventKind.ApprovalEscalated.class, name = "approval_escalated"),
        // Audit / run health
        @JsonSubTypes.Type(value = EventKind.AuditSealFailed.class, name = "audit_seal_failed"),
        @JsonSubTypes.Type(value = EventKind.RunStalled.class, name = "run_stalled"),
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
        @JsonSubTypes.Type(value = EventKind.AgentToolFailed.class, name = "agent_tool_failed"),
        // Continue-as-new
        @JsonSubTypes.Type(value = EventKind.SegmentBoundary.class, name = "segment_boundary"),
        // A kind this build does not know
        @JsonSubTypes.Type(value = EventKind.Unknown.class, name = "unknown")
})
public sealed interface EventKind {

    // ── Workflow lifecycle ──────────────────────────────────────────

    /**
     * @param initiatedBy principal that started the run ({@code "token:<id>"}), used for
     *                    separation of duties on approvals; {@code null} in dev mode and on old logs
     */
    record WorkflowStarted(
            String workflowId,
            String workflowVersion,
            JsonNode initialInput,
            String initiatedBy
    ) implements EventKind {
        public WorkflowStarted(String workflowId, String workflowVersion, JsonNode initialInput) {
            this(workflowId, workflowVersion, initialInput, null);
        }
    }

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
            ProvenanceMetadata provenance,
            String idempotencyKey
    ) implements EventKind {
        public NodeCompleted(String nodeId, JsonNode output, JsonNode statePatch, long durationMs,
                             String genAiSystem, String genAiModel, Long inputTokens, Long outputTokens,
                             String finishReason, Double costUsd, ProvenanceMetadata provenance) {
            this(nodeId, output, statePatch, durationMs, genAiSystem, genAiModel, inputTokens, outputTokens,
                    finishReason, costUsd, provenance, null);
        }
    }

    record NodeFailed(
            String nodeId,
            String error,
            int attempt,
            boolean retryable
    ) implements EventKind {}

    /**
     * A work item parked on a provider rate limit (429): reset to pending until
     * {@code retryAfter} (RFC 3339 text). Observability only; the node stays scheduled.
     */
    record NodeParked(
            String nodeId,
            int attempt,
            String retryAfter
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

    /**
     * @param principal       authenticated identity behind the decision ({@code "token:<id>"});
     *                        {@code null} in dev mode and on old logs
     * @param approvedDigest  the action digest this approval authorises; {@code null} on
     *                        rejections and on old logs, and a {@code null} never authorises a
     *                        digest-bound node
     * @param requestSequence sequence of the {@code ToolApprovalRequired} this decision answers
     */
    record ApprovalReceived(
            String nodeId,
            String userId,
            ApprovalDecision decision,
            String comment,
            JsonNode statePatch,
            String principal,
            String approvedDigest,
            Long requestSequence
    ) implements EventKind {
        public ApprovalReceived(String nodeId, String userId, ApprovalDecision decision, String comment,
                                JsonNode statePatch) {
            this(nodeId, userId, decision, comment, statePatch, null, null, null);
        }
    }

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

    /**
     * A node acting for {@code agents} was admitted to fire, charging one step to each and
     * {@code toolCalls} tool calls. {@code approvalSequence} is set when a human approval
     * released a step past a limit.
     */
    record AgentStepAdmitted(
            String nodeId,
            UUID workItemId,
            List<String> agents,
            int toolCalls,
            Long approvalSequence
    ) implements EventKind {
        public AgentStepAdmitted {
            agents = agents == null ? List.of() : List.copyOf(agents);
        }
    }

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

    /**
     * The approval request at {@code requestSequence} reached its deadline undecided: a
     * rejection by {@code principal} (always {@code "system:deadline"}).
     */
    record ApprovalExpired(
            String nodeId,
            long requestSequence,
            Instant expiresAt,
            String principal
    ) implements EventKind {}

    /**
     * The approval request at {@code requestSequence} is still pending at its escalation
     * time. Recorded once per request for notifiers; changes no approval state.
     */
    record ApprovalEscalated(
            String nodeId,
            long requestSequence,
            Instant escalateAt,
            Instant expiresAt
    ) implements EventKind {}

    // ── Audit / run health ──────────────────────────────────────────

    /**
     * The event {@code eventId} could not be sealed into the signed audit chain. The event
     * itself is durable; this marks the gap. Changes no state.
     */
    record AuditSealFailed(
            UUID eventId,
            long sequence,
            String eventType,
            String error
    ) implements EventKind {}

    /**
     * The scheduler stopped advancing this run because its log holds an event kind this
     * build cannot read ({@link Unknown}). Recorded once.
     */
    record RunStalled(
            String reason
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

    // ── Continue-as-new ─────────────────────────────────────────────

    /**
     * Written to the old execution when a continue-as-new rollover creates segment
     * {@code segmentNumber} as {@code nextExecutionId}. Audit only.
     */
    record SegmentBoundary(
            int segmentNumber,
            String nextExecutionId
    ) implements EventKind {}

    // ── Unknown ─────────────────────────────────────────────────────

    /**
     * An event kind this build does not know, written by a later release. Its original
     * {@code type} and fields are kept in memory ({@link #originalType()}, {@link #fields()})
     * for diagnostics; writing it back produces {@code {"type":"unknown"}}, as in the Rust
     * runtime. Treat it as possibly a denial or revocation: never as permission to proceed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    final class Unknown implements EventKind {
        private String originalType = "unknown";
        private final ObjectNode fields = JsonNodeFactory.instance.objectNode();

        public Unknown() {}

        public Unknown(String originalType, ObjectNode fields) {
            this.originalType = originalType == null ? "unknown" : originalType;
            if (fields != null) {
                this.fields.setAll(fields);
            }
        }

        /** The {@code type} the event was written with. */
        @JsonIgnore
        public String originalType() {
            return originalType;
        }

        /** The event's other fields, as written. */
        @JsonIgnore
        public ObjectNode fields() {
            return fields.deepCopy();
        }

        @JsonProperty(value = "type", access = JsonProperty.Access.WRITE_ONLY)
        void readType(String type) {
            this.originalType = type;
        }

        @JsonAnySetter
        void readField(String name, JsonNode value) {
            fields.set(name, value);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Unknown u && originalType.equals(u.originalType) && fields.equals(u.fields);
        }

        @Override
        public int hashCode() {
            return 31 * originalType.hashCode() + fields.hashCode();
        }

        @Override
        public String toString() {
            return "Unknown[type=" + originalType + ", fields=" + fields + "]";
        }
    }
}
