package dev.jamjet.cloud.spring;

import dev.jamjet.cloud.agentboundary.ActionReceipt;
import dev.jamjet.cloud.agentboundary.ActionReceiptEmitter;
import dev.jamjet.cloud.agentboundary.Actor;
import dev.jamjet.cloud.agentboundary.ActorType;
import dev.jamjet.cloud.agentboundary.Agent;
import dev.jamjet.cloud.agentboundary.Approval;
import dev.jamjet.cloud.agentboundary.Execution;
import dev.jamjet.cloud.agentboundary.ExecutionStatus;
import dev.jamjet.cloud.agentboundary.LoggingActionReceiptEmitter;
import dev.jamjet.cloud.agentboundary.Policy;
import dev.jamjet.cloud.agentboundary.PolicyDecision;
import dev.jamjet.cloud.agentboundary.ReceiptHashes;
import dev.jamjet.cloud.agentboundary.Target;
import dev.jamjet.cloud.agentboundary.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * LangChain4j {@link ToolExecutor} decorator that emits an AgentBoundary v0.1 Action Receipt
 * for every tool execution.
 *
 * <p>Wraps an inner {@link ToolExecutor} (constructor-injected). On every
 * {@link #execute(ToolExecutionRequest, Object)} call it:
 * <ol>
 *   <li>Computes {@code arguments_hash} via {@link ReceiptHashes#computeArgumentsHash(String)}</li>
 *   <li>Delegates to the inner executor and captures the result (or exception)</li>
 *   <li>Builds an {@link ActionReceipt} with a verifiable {@code receipt_hash}</li>
 *   <li>Calls {@link ActionReceiptEmitter#emit(ActionReceipt)}</li>
 *   <li>Returns the inner result (or re-throws the exception)</li>
 * </ol>
 *
 * <p>Emission failures are caught and logged at WARN — they never interrupt tool execution.
 *
 * <p>v1.8 defaults:
 * <ul>
 *   <li>{@code agent.framework = "langchain4j"}</li>
 *   <li>{@code agent.framework_version} — from {@code Package.getPackage("dev.langchain4j")} if available</li>
 *   <li>{@code agent.model} — constructor-supplied (LangChain4j ToolExecutor has no model access)</li>
 *   <li>{@code target.environment} — derived from Spring {@link org.springframework.core.env.Environment}
 *       active profiles ({@code prod} / {@code staging} / {@code dev})</li>
 *   <li>{@code target.system} — from {@code spring.application.name} or constructor override</li>
 *   <li>{@code policy.decision = ALLOW} (PolicyDecider is v1.9 work)</li>
 *   <li>{@code actor.type = AGENT} (LangChain4j tool calls are always agent-initiated)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * ToolExecutor wrapped = new ActionReceiptToolExecutor(
 *     rawExecutor, springEnvironment, emitter);
 * }</pre>
 */
public final class ActionReceiptToolExecutor implements ToolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ActionReceiptToolExecutor.class);

    private static final String FRAMEWORK = "langchain4j";
    private static final String FRAMEWORK_VERSION = resolveFrameworkVersion();

    private final ToolExecutor delegate;
    private final String model;
    private final org.springframework.core.env.Environment springEnv;
    private final ActionReceiptEmitter emitter;

    /**
     * Full constructor.
     *
     * @param delegate  the inner {@link ToolExecutor} to wrap
     * @param springEnv Spring {@link org.springframework.core.env.Environment} for profile / app-name resolution
     * @param emitter   the {@link ActionReceiptEmitter} to deliver receipts to
     */
    public ActionReceiptToolExecutor(
            ToolExecutor delegate,
            org.springframework.core.env.Environment springEnv,
            ActionReceiptEmitter emitter) {
        this(delegate, springEnv, emitter, "unknown");
    }

    /**
     * Full constructor with explicit model name.
     *
     * @param delegate  the inner {@link ToolExecutor} to wrap
     * @param springEnv Spring {@link org.springframework.core.env.Environment}
     * @param emitter   the {@link ActionReceiptEmitter} to deliver receipts to
     * @param model     LLM model identifier (e.g. {@code "gpt-4o"}) — LangChain4j's
     *                  ToolExecutor doesn't expose the model directly, so callers must supply it
     */
    public ActionReceiptToolExecutor(
            ToolExecutor delegate,
            org.springframework.core.env.Environment springEnv,
            ActionReceiptEmitter emitter,
            String model) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (springEnv == null) throw new IllegalArgumentException("springEnv must not be null");
        if (emitter == null) throw new IllegalArgumentException("emitter must not be null");
        this.delegate = delegate;
        this.springEnv = springEnv;
        this.emitter = emitter;
        this.model = (model != null && !model.isBlank()) ? model : "unknown";
    }

    /**
     * Convenience constructor that uses {@link LoggingActionReceiptEmitter}.
     *
     * @param delegate  the inner {@link ToolExecutor} to wrap
     * @param springEnv Spring {@link org.springframework.core.env.Environment}
     */
    public ActionReceiptToolExecutor(
            ToolExecutor delegate,
            org.springframework.core.env.Environment springEnv) {
        this(delegate, springEnv, new LoggingActionReceiptEmitter(), "unknown");
    }

    // -------------------------------------------------------------------------
    // ToolExecutor contract
    // -------------------------------------------------------------------------

    /**
     * Execute the tool via the inner executor and emit an AgentBoundary Action Receipt.
     *
     * @param request  the tool execution request from LangChain4j
     * @param memoryId the memory/session identifier passed by the LangChain4j service layer
     * @return the tool result string from the inner executor
     */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String toolName = request.name();
        String arguments = request.arguments();

        String argsHash;
        try {
            argsHash = ReceiptHashes.computeArgumentsHash(arguments);
        } catch (Throwable t) {
            LOG.warn("ActionReceiptToolExecutor: failed to compute arguments_hash for tool '{}': {}",
                toolName, t.getMessage());
            argsHash = ReceiptHashes.computeArgumentsHash(null);
        }

        dev.jamjet.cloud.agentboundary.Environment env = resolveEnvironment();
        String system = resolveSystem();
        String completedAt = Instant.now().toString();

        String result;
        ExecutionStatus status;
        String errorCode = null;

        try {
            result = delegate.execute(request, memoryId);
            status = ExecutionStatus.SUCCESS;
        } catch (Throwable t) {
            status = ExecutionStatus.FAILURE;
            errorCode = t.getClass().getSimpleName();
            completedAt = Instant.now().toString();
            emitReceipt(toolName, argsHash, env, system, completedAt, status, errorCode);
            throw t;
        }

        completedAt = Instant.now().toString();
        emitReceipt(toolName, argsHash, env, system, completedAt, status, errorCode);
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void emitReceipt(
            String toolName,
            String argsHash,
            dev.jamjet.cloud.agentboundary.Environment env,
            String system,
            String completedAt,
            ExecutionStatus status,
            String errorCode) {
        try {
            String receiptId = UUID.randomUUID().toString();
            String issuedAt = Instant.now().toString();

            Actor actor = new Actor(ActorType.AGENT, "langchain4j-agent", null);
            Agent agent = new Agent(FRAMEWORK, FRAMEWORK_VERSION, model, null);
            Tool tool = new Tool(toolName, null, toolName);
            Target target = new Target(system, env, null);
            Policy policy = new Policy("default.allow", "1", PolicyDecision.ALLOW);
            Execution execution = new Execution(status, completedAt, errorCode, null);

            // receipt_hash = SHA-256 of canonical JSON of all receipt fields EXCEPT receipt_hash itself.
            // Per AgentBoundary v0.1 spec §4.12.
            String receiptHash = ReceiptHashes.computeReceiptHash(
                ActionReceipt.CURRENT_VERSION, receiptId, issuedAt,
                actor, agent, tool, target, argsHash, policy, null, execution);

            ActionReceipt receipt = new ActionReceipt(
                ActionReceipt.CURRENT_VERSION,
                receiptId,
                issuedAt,
                actor,
                agent,
                tool,
                target,
                argsHash,
                policy,
                null,       // no approval required for ALLOW decision
                execution,
                receiptHash
            );

            emitter.emit(receipt);
        } catch (Throwable t) {
            LOG.warn("ActionReceiptToolExecutor: failed to build/emit receipt for tool '{}': {}",
                toolName, t.getMessage());
        }
    }

    private dev.jamjet.cloud.agentboundary.Environment resolveEnvironment() {
        String[] profiles = springEnv.getActiveProfiles();
        for (String p : profiles) {
            if ("prod".equalsIgnoreCase(p)) return dev.jamjet.cloud.agentboundary.Environment.PROD;
            if ("staging".equalsIgnoreCase(p)) return dev.jamjet.cloud.agentboundary.Environment.STAGING;
        }
        return dev.jamjet.cloud.agentboundary.Environment.DEV;
    }

    private String resolveSystem() {
        String name = springEnv.getProperty("spring.application.name");
        return (name != null && !name.isBlank()) ? name : "unknown";
    }

    private static String resolveFrameworkVersion() {
        try {
            Package pkg = Package.getPackage("dev.langchain4j");
            if (pkg != null) {
                String v = pkg.getImplementationVersion();
                if (v != null && !v.isBlank()) return v;
                v = pkg.getSpecificationVersion();
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Throwable ignored) { /* defensive */ }
        return "unknown";
    }
}
