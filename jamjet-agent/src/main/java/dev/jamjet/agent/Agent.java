package dev.jamjet.agent;

import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.tools.ToolRegistry;
import dev.jamjet.runtime.core.ir.PolicySetIr;
import dev.jamjet.runtime.core.ir.WorkflowIr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Idiomatic Java authoring for a governed, durable JamJet agent — the Java analog
 * of the Python {@code jamjet.Agent}. A {@code model} + {@code @Tool} methods +
 * {@code instructions} + governance knobs, compiled by {@link #compileToIr()} to
 * the <em>same</em> agent-loop {@link WorkflowIr} the Python ADK emits, so a Java
 * agent and a Python agent run on the identical Rust engine (the Java tool nodes
 * being {@code java_fn} where Python's are {@code python_fn}).
 *
 * <p>Construct via the {@link #builder(String)} fluent builder:
 * <pre>{@code
 * Agent agent = Agent.builder("research_agent")
 *         .model("anthropic/claude-sonnet-4-6")
 *         .instructions("You are a helpful research assistant.")
 *         .tools(new WebSearchTools(), new MathTools())
 *         .policy(new PolicySetIr(List.of("delete_db"), List.of(), List.of()))
 *         .approvalRequired(List.of("delete_*"))
 *         .budget(new Budget(100_000, 2.5))
 *         .build();
 *
 * WorkflowIr ir = agent.compileToIr();   // the durable agent-loop IR
 * }</pre>
 *
 * <p>The model side is free: a Java agent emits only {@code Model} nodes; the Rust
 * engine routes model calls through the governed Python model-seam sidecar. No
 * Java model code is needed.
 */
public final class Agent {

    /** Default static-unroll bound for the agent loop (mirrors the Python default). */
    public static final int DEFAULT_MAX_TURNS = 8;

    private final String name;
    private final String model;
    private final String instructions;
    private final String strategy;
    private final ToolRegistry registry;
    private final PolicySetIr policy;
    private final boolean approvalAll;
    private final List<String> approvalGlobs;
    private final Budget budget;
    private final boolean pii;
    private final int timeoutSeconds;

    private Agent(Builder b) {
        this.name = b.name;
        this.model = b.model;
        this.instructions = b.instructions;
        this.strategy = b.strategy;
        this.registry = b.registry;
        this.policy = b.policy;
        this.approvalAll = b.approvalAll;
        this.approvalGlobs = List.copyOf(b.approvalGlobs);
        this.budget = b.budget;
        this.pii = b.pii;
        this.timeoutSeconds = b.timeoutSeconds;
    }

    /** Start building an agent with the given logical name (used as the workflow id). */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Compile this agent to the durable agent-loop {@link WorkflowIr} with the
     * default unroll bound ({@link #DEFAULT_MAX_TURNS}).
     */
    public WorkflowIr compileToIr() {
        return compileToIr(DEFAULT_MAX_TURNS);
    }

    /**
     * Compile this agent to the durable agent-loop {@link WorkflowIr}, statically
     * unrolling up to {@code maxTurns} {@code model -> tools} turns plus a final
     * tool-less answer turn. The start node is always the first Model node.
     */
    public WorkflowIr compileToIr(int maxTurns) {
        return AgentIrCompiler.compile(this, maxTurns);
    }

    // -- durable run (B-4) ------------------------------------------------------

    /**
     * Run this agent durably on the JamJet engine with the default {@link RunOptions}
     * (local runtime, {@link #DEFAULT_MAX_TURNS} turns), returning the final assistant
     * text + tool-call trace as an {@link AgentResult}. The Java mirror of the Python
     * {@code Agent.run_durable}.
     *
     * <p>This compiles the agent to the agent-loop {@link WorkflowIr}, registers it
     * ({@code POST /workflows}), starts an execution seeded with the system+user
     * {@code messages} ({@code POST /executions}), polls {@code GET /executions/{id}}
     * to a terminal state, and extracts the answer from the terminal
     * {@code current_state.last_model_output} (falling back to the last assistant
     * message). Every model call and tool dispatch runs through the durable engine, so
     * the run is event-sourced, replayable, idempotent, and governed: budget and policy
     * (the model allowlist plus approval gates) are enforced fail-closed by the engine,
     * and PII redaction is applied at the model-seam sidecar (the {@code data_policy} IR
     * signals it; redaction is the sidecar's job, not an IR-level guarantee).
     *
     * <h2>Required running services (mirrors the Python {@code run_durable})</h2>
     * A durable run is NOT self-contained — three services must be running:
     * <ol>
     *   <li><b>the JamJet engine</b> at {@link RunOptions#runtimeUrl()} (the
     *       {@code jamjet-server} that owns the {@code java_tool} queue);</li>
     *   <li><b>the model sidecar</b> ({@code JAMJET_MODEL_SEAM_URL}) — the engine routes
     *       every governed model call through it (no Java model code);</li>
     *   <li><b>a {@link dev.jamjet.agent.worker.JavaToolWorker}</b> draining the
     *       {@code java_tool} queue with THIS agent's tool registry, so the
     *       {@code @Tool} methods execute durably exactly-once. Run it in a separate
     *       thread/process; {@code runDurable} does not start one.</li>
     * </ol>
     *
     * @throws AgentRunException        if the run reaches a non-{@code completed} terminal
     *                                  state ({@code failed} / {@code cancelled} /
     *                                  {@code limit_exceeded})
     * @throws AgentRunTimeoutException if no terminal state is reached before the deadline
     */
    public AgentResult runDurable(String prompt) {
        return runDurable(prompt, RunOptions.defaults());
    }

    /**
     * Run this agent durably with the given {@link RunOptions}, building (and closing) a
     * {@link JamjetEngineClient} for {@link RunOptions#runtimeUrl()}. See
     * {@link #runDurable(String)} for the running-services contract.
     */
    public AgentResult runDurable(String prompt, RunOptions options) {
        try (JamjetEngineClient client =
                     new JamjetEngineClient(options.runtimeUrl(), options.bearerToken(), options.tenantId())) {
            return runDurable(prompt, client, options);
        }
    }

    /**
     * Run this agent durably over a caller-provided {@link JamjetEngineClient}. The
     * caller owns the client's lifecycle (this overload does NOT close it), so a run
     * and a {@link dev.jamjet.agent.worker.JavaToolWorker} can share one client against
     * the same engine. See {@link #runDurable(String)} for the running-services contract.
     */
    public AgentResult runDurable(String prompt, JamjetEngineClient client, RunOptions options) {
        return DurableRunner.run(this, prompt, client, options);
    }

    // -- accessors (read by AgentIrCompiler) ------------------------------------

    public String name() {
        return name;
    }

    public String model() {
        return model;
    }

    public String instructions() {
        return instructions;
    }

    public String strategy() {
        return strategy;
    }

    public ToolRegistry registry() {
        return registry;
    }

    /** The inline policy, or {@code null} when none is set. */
    public PolicySetIr policy() {
        return policy;
    }

    /** {@code true} when every tool call requires human approval ({@code approvalRequired(true)}). */
    public boolean approvalAll() {
        return approvalAll;
    }

    /** The per-tool approval globs (possibly empty). */
    public List<String> approvalGlobs() {
        return approvalGlobs;
    }

    /** The per-run budget, or {@code null} when uncapped. */
    public Budget budget() {
        return budget;
    }

    /** Whether PII governance is on (emits the default {@code data_policy} IR). */
    public boolean pii() {
        return pii;
    }

    /** The workflow timeout in seconds (compiled into {@code timeouts.workflow_timeout}). */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public String toString() {
        return "Agent(name=" + name + ", model=" + model
                + ", tools=" + registry.tools().stream().map(t -> t.name()).toList()
                + ", strategy=" + strategy + ")";
    }

    /** Fluent builder for {@link Agent}. */
    public static final class Builder {
        private final String name;
        private String model;
        private String instructions = "";
        private String strategy = "react";
        private ToolRegistry registry = new ToolRegistry();
        private PolicySetIr policy;
        private boolean approvalAll;
        private List<String> approvalGlobs = List.of();
        private Budget budget;
        private boolean pii = true;
        private int timeoutSeconds = 300;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "agent name must not be null");
        }

        /** The model reference, e.g. {@code "anthropic/claude-sonnet-4-6"}. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** The system instructions for the agent. */
        public Builder instructions(String instructions) {
            this.instructions = instructions == null ? "" : instructions;
            return this;
        }

        /**
         * The reasoning strategy. v1 always compiles the durable IR as the
         * react-style {@code model -> tools -> model} loop regardless of this
         * value (parity with the Python durable path); the field is carried for
         * forward compatibility.
         */
        public Builder strategy(String strategy) {
            this.strategy = strategy == null ? "react" : strategy;
            return this;
        }

        /** The tool-holder instances whose {@code @Tool} methods the agent may call. */
        public Builder tools(Object... holders) {
            this.registry = ToolRegistry.of(holders);
            return this;
        }

        /** The tool-holder instances whose {@code @Tool} methods the agent may call. */
        public Builder tools(List<?> holders) {
            this.registry = ToolRegistry.of(holders);
            return this;
        }

        /** Use a pre-built tool registry. */
        public Builder registry(ToolRegistry registry) {
            this.registry = registry == null ? new ToolRegistry() : registry;
            return this;
        }

        /** An inline policy ({@code blocked_tools} / {@code require_approval_for} / {@code model_allowlist}). */
        public Builder policy(PolicySetIr policy) {
            this.policy = policy;
            return this;
        }

        /**
         * Require human approval for tools. {@code true} requires approval for
         * every tool call ({@code require_approval_for = ["*"]}); {@code false}
         * clears the gate.
         */
        public Builder approvalRequired(boolean all) {
            this.approvalAll = all;
            this.approvalGlobs = List.of();
            return this;
        }

        /**
         * Require human approval for the given tool-name globs (e.g.
         * {@code ["delete_*", "send_*"]}). Unioned into {@code require_approval_for}.
         */
        public Builder approvalRequired(List<String> globs) {
            this.approvalAll = false;
            this.approvalGlobs = globs == null ? List.of() : new ArrayList<>(globs);
            return this;
        }

        /** The per-run budget (token and/or cost cap). */
        public Builder budget(Budget budget) {
            this.budget = budget;
            return this;
        }

        /** Toggle PII governance (on by default). */
        public Builder pii(boolean pii) {
            this.pii = pii;
            return this;
        }

        /** The workflow timeout in seconds (default 300; must be positive). */
        public Builder timeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("timeoutSeconds must be positive (got " + timeoutSeconds + ")");
            }
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /** Build the immutable {@link Agent}. */
        public Agent build() {
            Objects.requireNonNull(model, "agent model must be set");
            if (model.isBlank()) {
                throw new IllegalArgumentException("agent model must not be blank");
            }
            return new Agent(this);
        }
    }
}
