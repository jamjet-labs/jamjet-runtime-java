package dev.jamjet.agent.examples;

import dev.jamjet.agent.Agent;
import dev.jamjet.agent.AgentResult;
import dev.jamjet.agent.Budget;
import dev.jamjet.agent.RunOptions;
import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.worker.JavaToolWorker;
import dev.jamjet.runtime.core.ir.PolicySetIr;

import java.util.List;

/**
 * End-to-end example: a governed, durable calculator agent authored in idiomatic Java.
 *
 * <p>{@link #buildAgent()} is the whole authoring surface — a {@link Agent} with a model,
 * instructions, one {@code @Tool} ({@link CalculatorTools#calculate}), and governance
 * defaults (a token+cost {@link Budget}, an inline {@link PolicySetIr} with a blocked
 * tool + a model allowlist, and an approval gate). It compiles to the SAME agent-loop
 * {@code WorkflowIr} the Python ADK emits, so it runs on the identical Rust engine; the
 * governance knobs ride in the IR and the engine enforces them fail-closed.
 *
 * <h2>Running it for real ({@link #main})</h2>
 * A durable run needs three services live (mirrors the Python {@code agent.run_durable}):
 * <ol>
 *   <li><b>the JamJet engine</b> ({@code jamjet-server}) at {@link RunOptions#runtimeUrl()}
 *       — it owns the {@code java_tool} queue;</li>
 *   <li><b>the model sidecar</b> ({@code JAMJET_MODEL_SEAM_URL}) — the engine routes every
 *       governed model call through it;</li>
 *   <li><b>a {@link JavaToolWorker}</b> draining the {@code java_tool} queue with this
 *       agent's tool registry, so {@code calculate} executes durably exactly-once.</li>
 * </ol>
 * {@code main} starts the worker on a background thread and shares ONE
 * {@link JamjetEngineClient} between the worker and the run. (The hermetic
 * {@code CalculatorAgentExampleTest} drives this same agent against a WireMock stub of
 * the engine, since a live engine + sidecar is not available in CI.)
 *
 * <p>This example uses only the real shipped API — no fictional methods.
 */
public final class CalculatorAgentExample {

    private CalculatorAgentExample() {}

    /** The complete authoring surface: a governed, tool-using durable agent in idiomatic Java. */
    public static Agent buildAgent() {
        return Agent.builder("calculator_agent")
                .model("anthropic/claude-sonnet-4-6")
                .instructions("You are a precise calculator. Use the calculate tool for every arithmetic step.")
                .tools(new CalculatorTools())
                // -- governance: compiled into the IR, enforced fail-closed by the engine --
                .budget(new Budget(50_000, 0.50))                 // cap tokens AND cost
                .policy(new PolicySetIr(
                        List.of("delete_*"),                      // blocked_tools
                        List.of(),                                // require_approval_for (from globs below)
                        List.of("anthropic/claude-sonnet-4-6")))  // model_allowlist
                .approvalRequired(List.of("wire_*"))              // HITL gate on any wire_* tool
                .pii(true)                                        // PII redaction metadata in the IR
                .build();
    }

    /**
     * Run the agent durably against a local engine, with a background
     * {@link JavaToolWorker} executing the {@code @Tool} call. Requires the three
     * services above to be running; intended as a copy-paste starting point, not a CI test.
     */
    public static void main(String[] args) {
        Agent agent = buildAgent();
        RunOptions options = RunOptions.defaults();  // local engine at http://127.0.0.1:7700

        try (JamjetEngineClient client = new JamjetEngineClient(options.runtimeUrl());
             JavaToolWorker worker = new JavaToolWorker(client, "calculator-worker-1", agent.registry())) {

            // Drain the java_tool queue on a background thread for the duration of the run.
            Thread workerThread = new Thread(worker::run, "calculator-java-tool-worker");
            workerThread.setDaemon(true);
            workerThread.start();

            AgentResult result = agent.runDurable("What is (5 + 3) * 2?", client, options);

            System.out.println("answer:    " + result.output());
            System.out.println("toolCalls: " + result.toolCalls());

            worker.stop();
        }
    }
}
