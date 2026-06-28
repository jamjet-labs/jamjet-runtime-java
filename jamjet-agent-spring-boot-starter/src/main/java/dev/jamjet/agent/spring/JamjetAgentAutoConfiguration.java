package dev.jamjet.agent.spring;

import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.tools.ToolRegistry;
import dev.jamjet.agent.worker.JavaToolWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Spring Boot auto-configuration for the governed durable JamJet {@code Agent} — the
 * one-dependency authoring surface for the framework-free {@code jamjet-agent}.
 *
 * <p>It exposes, all overridable via {@link ConditionalOnMissingBean}:
 * <ul>
 *   <li>a {@link JamjetEngineClient} built from {@link JamjetAgentProperties} (the bare-route
 *       transport to the Rust engine);</li>
 *   <li>a {@link ToolRegistry} populated from {@code @Tool}-annotated Spring beans by the
 *       {@link ToolBeanRegistrar} — the SAME registry a user's {@code Agent} should be built
 *       with ({@code Agent.builder(...).registry(registry)}) so the agent's IR and the worker
 *       share one tool set;</li>
 *   <li>a {@link JavaToolWorker} wrapped in a {@link JavaToolWorkerLifecycle} that drains the
 *       {@code java_tool} queue on a daemon thread for the life of the context, gated on
 *       {@code jamjet.agent.worker.enabled} (default {@code true}).</li>
 * </ul>
 *
 * <p>This config wraps ONLY framework-free types ({@code jamjet-agent} + JDK), so its bean
 * return types always link — it cannot re-trigger the {@code @ConditionalOnClass}
 * {@code NoClassDefFoundError} class of bug. The engine routes governed model calls through
 * the external model-seam sidecar, so there is deliberately no Spring AI / LangChain4j here.
 *
 * <h2>Not self-contained</h2>
 * A real durable run still needs the external JamJet engine ({@code runtime-url}) and the
 * model-seam sidecar ({@code JAMJET_MODEL_SEAM_URL}) running. This starter auto-provides only
 * the worker side of that contract (see {@code Agent.runDurable}).
 */
@AutoConfiguration
@EnableConfigurationProperties(JamjetAgentProperties.class)
public class JamjetAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JamjetAgentAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public JamjetEngineClient jamjetEngineClient(JamjetAgentProperties properties) {
        log.info("JamJet: engine client -> {}", properties.getRuntimeUrl());
        return new JamjetEngineClient(
                properties.getRuntimeUrl(),
                properties.getBearerToken(),
                properties.getTenantId());
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry jamjetToolRegistry() {
        // Empty here; ToolBeanRegistrar populates it from @Tool Spring beans after all
        // singletons are instantiated. The JavaToolWorker holds this same instance by
        // reference and resolves tools lazily by name at dispatch time.
        return new ToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "jamjet.agent.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    public JavaToolWorkerLifecycle javaToolWorkerLifecycle(JamjetEngineClient client,
                                                           ToolRegistry registry,
                                                           JamjetAgentProperties properties) {
        JamjetAgentProperties.Worker w = properties.getWorker();
        JavaToolWorker worker = new JavaToolWorker(
                client,
                w.getId(),
                registry,
                Duration.ofMillis(w.getHeartbeatMs()),
                Duration.ofMillis(w.getPollBackoffMs()));
        return new JavaToolWorkerLifecycle(worker, "jamjet-java-tool-worker[" + w.getId() + "]");
    }
}
