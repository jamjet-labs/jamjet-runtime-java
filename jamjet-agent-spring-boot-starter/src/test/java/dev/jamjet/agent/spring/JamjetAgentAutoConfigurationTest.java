package dev.jamjet.agent.spring;

import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice tests for {@link JamjetAgentAutoConfiguration} (C-1): the beans wire from
 * properties, the worker is a lifecycle bean (present/absent per
 * {@code jamjet.agent.worker.enabled}), and an application can override any bean.
 */
class JamjetAgentAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetAgentAutoConfiguration.class))
            // Keep the background worker off by default so the wiring tests stay fast + quiet.
            .withPropertyValues("jamjet.agent.worker.enabled=false");

    @Test
    void createsEngineClientAndToolRegistry() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(JamjetEngineClient.class);
            assertThat(ctx).hasSingleBean(ToolRegistry.class);
            assertThat(ctx).hasSingleBean(JamjetAgentProperties.class);
        });
    }

    @Test
    void propertiesBindFromTheJamjetAgentPrefix() {
        runner
                .withPropertyValues(
                        "jamjet.agent.runtime-url=http://engine.example:9100",
                        "jamjet.agent.bearer-token=secret-token",
                        "jamjet.agent.tenant-id=acme",
                        "jamjet.agent.worker.id=worker-7",
                        "jamjet.agent.worker.heartbeat-ms=3000",
                        "jamjet.agent.worker.poll-backoff-ms=750"
                )
                .run(ctx -> {
                    JamjetAgentProperties props = ctx.getBean(JamjetAgentProperties.class);
                    assertThat(props.getRuntimeUrl()).isEqualTo("http://engine.example:9100");
                    assertThat(props.getBearerToken()).isEqualTo("secret-token");
                    assertThat(props.getTenantId()).isEqualTo("acme");
                    assertThat(props.getWorker().isEnabled()).isFalse();
                    assertThat(props.getWorker().getId()).isEqualTo("worker-7");
                    assertThat(props.getWorker().getHeartbeatMs()).isEqualTo(3000);
                    assertThat(props.getWorker().getPollBackoffMs()).isEqualTo(750);
                });
    }

    @Test
    void runtimeUrlDefaultsToTheLocalEngine() {
        runner.run(ctx -> {
            JamjetAgentProperties props = ctx.getBean(JamjetAgentProperties.class);
            assertThat(props.getRuntimeUrl()).isEqualTo("http://127.0.0.1:7700");
            assertThat(props.getWorker().getHeartbeatMs()).isEqualTo(10_000);
            assertThat(props.getWorker().getPollBackoffMs()).isEqualTo(2_000);
        });
    }

    @Test
    void workerLifecycleBeanIsPresentAndRunningWhenEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JamjetAgentAutoConfiguration.class))
                // Default worker.enabled=true; point at an unused port so the background
                // drain fails fast (connection refused) instead of hitting a real engine.
                .withPropertyValues(
                        "jamjet.agent.runtime-url=http://127.0.0.1:1",
                        "jamjet.agent.worker.poll-backoff-ms=200")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JavaToolWorkerLifecycle.class);
                    JavaToolWorkerLifecycle lifecycle = ctx.getBean(JavaToolWorkerLifecycle.class);
                    assertThat(lifecycle).isInstanceOf(SmartLifecycle.class);
                    // SmartLifecycle auto-starts with the context.
                    assertThat(lifecycle.isRunning()).isTrue();
                });
    }

    @Test
    void workerLifecycleBeanIsAbsentWhenDisabled() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(JavaToolWorkerLifecycle.class));
    }

    @Test
    void applicationCanOverrideTheEngineClient() {
        JamjetEngineClient custom = new JamjetEngineClient("http://override.example:8080");
        runner
                .withBean("myEngineClient", JamjetEngineClient.class, () -> custom)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JamjetEngineClient.class);
                    assertThat(ctx.getBean(JamjetEngineClient.class)).isSameAs(custom);
                });
    }

    @Test
    void applicationCanOverrideTheToolRegistry() {
        ToolRegistry custom = new ToolRegistry();
        runner
                .withBean("myToolRegistry", ToolRegistry.class, () -> custom)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ToolRegistry.class);
                    assertThat(ctx.getBean(ToolRegistry.class)).isSameAs(custom);
                });
    }
}
