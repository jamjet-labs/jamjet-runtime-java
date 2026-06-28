package dev.jamjet.agent.spring.example;

import dev.jamjet.agent.Agent;
import dev.jamjet.agent.AgentResult;
import dev.jamjet.agent.RunOptions;
import dev.jamjet.agent.client.JamjetEngineClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * A copy-paste worked example: a governed, durable JamJet agent authored in idiomatic Spring.
 * Adding the {@code jamjet-agent-spring-boot-starter} dependency auto-configures the engine
 * client, the {@code @Tool}-scanned {@link dev.jamjet.agent.tools.ToolRegistry}, and a background
 * {@link dev.jamjet.agent.worker.JavaToolWorker}; this app only declares its {@code @Tool}
 * {@link CalculatorTools} bean, the {@link Agent} bean (see {@link CalculatorAgentConfig}), and a
 * runner that drives it.
 *
 * <h2>Running it for real</h2>
 * A durable run needs the external JamJet engine ({@code jamjet.agent.runtime-url}) and the
 * model-seam sidecar ({@code JAMJET_MODEL_SEAM_URL}) running. The auto-configured worker drains
 * {@code java_tool} in the background, sharing the same {@link JamjetEngineClient} this runner
 * passes to {@code runDurable}. This class is a compile-checked example (it uses only the real
 * shipped API); the CI gate is the {@code ApplicationContextRunner} slice test, which needs no
 * live engine.
 */
@SpringBootApplication
public class CalculatorSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalculatorSpringApplication.class, args);
    }

    /**
     * Inject the auto-configured {@link Agent} and {@link JamjetEngineClient} and run the agent
     * durably. Sharing the auto-configured client with the background worker is the intended
     * pattern: {@code runDurable(prompt, client, options)} drives the run over the same engine
     * connection the worker drains, so the run and its tool execution stay on one client.
     */
    @Bean
    public CommandLineRunner runCalculator(Agent agent, JamjetEngineClient client) {
        return args -> {
            AgentResult result = agent.runDurable("What is (5 + 3) * 2?", client, RunOptions.defaults());
            System.out.println("answer:    " + result.output());
            System.out.println("toolCalls: " + result.toolCalls());
        };
    }
}
