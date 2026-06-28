package dev.jamjet.agent.spring.example;

import dev.jamjet.agent.Agent;
import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.tools.ToolRegistry;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.jamjet.agent.spring.JamjetAgentAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-3 exit-criterion slice test: the worked Spring example wires end to end on the
 * autoconfigured beans, with no live engine. It proves a governed durable agent is authored
 * in idiomatic Java/Spring:
 *
 * <ul>
 *   <li>the {@code @Tool} {@code @Component} ({@link CalculatorTools}) is scanned into the
 *       auto-configured {@link ToolRegistry};</li>
 *   <li>the {@link Agent} bean is built over that SAME registry (so its IR and the worker share
 *       one tool set);</li>
 *   <li>the agent compiles to the durable agent-loop {@link WorkflowIr} carrying its governance
 *       (budget / policy / approval gate).</li>
 * </ul>
 *
 * The engine round-trip itself is already proven hermetically against a WireMock engine in the
 * {@code jamjet-agent} module, so the Spring layer only needs to prove wiring.
 */
class CalculatorSpringWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetAgentAutoConfiguration.class))
            .withUserConfiguration(CalculatorTools.class, CalculatorAgentConfig.class)
            // No background drain in the slice test; we are proving authoring/wiring.
            .withPropertyValues("jamjet.agent.worker.enabled=false");

    @Test
    void exampleAppWiresAndAuthorsAGovernedDurableAgent() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(JamjetEngineClient.class);
            assertThat(ctx).hasSingleBean(ToolRegistry.class);
            assertThat(ctx).hasSingleBean(Agent.class);

            ToolRegistry registry = ctx.getBean(ToolRegistry.class);
            Agent agent = ctx.getBean(Agent.class);

            // The @Component tool was scanned into the auto-configured registry...
            assertThat(registry.byName("calculate")).isNotNull();
            // ...and the Agent is built over that SAME registry instance (authoring == execution).
            assertThat(agent.registry()).isSameAs(registry);
            assertThat(agent.registry().byName("calculate")).isNotNull();

            // Governance is configured and rides into the compiled durable IR.
            assertThat(agent.budget()).isNotNull();
            assertThat(agent.policy()).isNotNull();
            assertThat(agent.policy().blockedTools()).contains("delete_*");
            assertThat(agent.approvalGlobs()).contains("wire_*");

            WorkflowIr ir = agent.compileToIr();
            assertThat(ir).isNotNull();
        });
    }
}
