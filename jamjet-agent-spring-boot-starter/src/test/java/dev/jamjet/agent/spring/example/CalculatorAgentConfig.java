package dev.jamjet.agent.spring.example;

import dev.jamjet.agent.Agent;
import dev.jamjet.agent.Budget;
import dev.jamjet.agent.tools.ToolRegistry;
import dev.jamjet.runtime.core.ir.PolicySetIr;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Declares the governed {@link Agent} as a Spring bean, built over the auto-configured
 * {@link ToolRegistry}. Injecting that shared registry via {@code .registry(registry)} (rather
 * than {@code .tools(new CalculatorTools())}) is the load-bearing step: it makes the agent's
 * compiled IR offer the model exactly the {@code @Tool} beans the background
 * {@link dev.jamjet.agent.worker.JavaToolWorker} can dispatch, so authoring and execution share
 * one tool set.
 *
 * <p>The governance knobs (budget, policy, approval gate, PII) compile into the agent-loop
 * {@code WorkflowIr} and are enforced fail-closed by the Rust engine.
 */
@Configuration(proxyBeanMethods = false)
public class CalculatorAgentConfig {

    @Bean
    public Agent calculatorAgent(ToolRegistry toolRegistry) {
        return Agent.builder("calculator_agent")
                .model("anthropic/claude-sonnet-4-6")
                .instructions("You are a precise calculator. Use the calculate tool for every arithmetic step.")
                // The auto-configured, @Tool-populated registry — shared with the worker.
                .registry(toolRegistry)
                // -- governance: compiled into the IR, enforced fail-closed by the engine --
                .budget(new Budget(50_000, 0.50))                  // cap tokens AND cost
                .policy(new PolicySetIr(
                        List.of("delete_*"),                       // blocked_tools
                        List.of(),                                 // require_approval_for (from the glob below)
                        List.of("anthropic/claude-sonnet-4-6")))   // model_allowlist
                .approvalRequired(List.of("wire_*"))               // HITL gate on any wire_* tool
                .pii(true)                                         // PII redaction metadata in the IR
                .build();
    }
}
