package dev.jamjet.agent;

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

        /** The workflow timeout in seconds (default 300). */
        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /** Build the immutable {@link Agent}. */
        public Agent build() {
            Objects.requireNonNull(model, "agent model must be set");
            return new Agent(this);
        }
    }
}
