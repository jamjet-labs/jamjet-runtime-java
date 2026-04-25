package dev.jamjet.runtime.spring;

import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;
import dev.jamjet.runtime.core.scheduler.Scheduler;
import dev.jamjet.runtime.core.scheduler.SchedulerConfig;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.worker.WorkerPool;
import dev.jamjet.runtime.plugins.PluginLoader;
import dev.jamjet.runtime.plugins.PluginRegistry;
import dev.jamjet.runtime.protocols.ProtocolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JamjetAutoConfiguration}.
 */
class JamjetAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JamjetAutoConfiguration.class);

    @Test
    void defaultConfigCreatesAllRequiredBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(StateBackend.class);
            assertThat(ctx).hasSingleBean(NodeExecutorRegistry.class);
            assertThat(ctx).hasSingleBean(ProtocolRegistry.class);
            assertThat(ctx).hasSingleBean(PluginRegistry.class);
            assertThat(ctx).hasSingleBean(PluginLoader.class);
            assertThat(ctx).hasSingleBean(SchedulerConfig.class);
            assertThat(ctx).hasSingleBean(Scheduler.class);
            assertThat(ctx).hasSingleBean(WorkerPool.class);
            assertThat(ctx).hasSingleBean(JamjetRestController.class);
            assertThat(ctx).hasSingleBean(DurableAgentBeanPostProcessor.class);
        });
    }

    @Test
    void stateBackendDefaultsToInMemory() {
        runner.run(ctx -> {
            StateBackend backend = ctx.getBean(StateBackend.class);
            assertThat(backend).isInstanceOf(InMemoryStateBackend.class);
        });
    }

    @Test
    void customStateBackendBeanOverridesDefault() {
        StateBackend customBackend = new InMemoryStateBackend();

        runner.withBean(StateBackend.class, () -> customBackend)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(StateBackend.class);
                    StateBackend registered = ctx.getBean(StateBackend.class);
                    assertThat(registered).isSameAs(customBackend);
                });
    }

    @Test
    void propertiesBindCorrectly() {
        runner
                .withPropertyValues(
                        "jamjet.storage=in-memory",
                        "jamjet.scheduler.poll-interval-ms=250",
                        "jamjet.scheduler.max-concurrent-nodes=8",
                        "jamjet.scheduler.max-dispatch-per-tick=4",
                        "jamjet.workers.concurrency=2",
                        "jamjet.workers.queue-types=model,tool",
                        "jamjet.plugins.directory=/tmp/plugins",
                        "jamjet.plugins.enabled=false"
                )
                .run(ctx -> {
                    JamjetProperties props = ctx.getBean(JamjetProperties.class);
                    assertThat(props.getStorage()).isEqualTo("in-memory");
                    assertThat(props.getScheduler().getPollIntervalMs()).isEqualTo(250);
                    assertThat(props.getScheduler().getMaxConcurrentNodes()).isEqualTo(8);
                    assertThat(props.getScheduler().getMaxDispatchPerTick()).isEqualTo(4);
                    assertThat(props.getWorkers().getConcurrency()).isEqualTo(2);
                    assertThat(props.getWorkers().getQueueTypes()).isEqualTo("model,tool");
                    assertThat(props.getPlugins().getDirectory()).isEqualTo("/tmp/plugins");
                    assertThat(props.getPlugins().isEnabled()).isFalse();
                });
    }

    @Test
    void schedulerConfigReflectsProperties() {
        runner
                .withPropertyValues(
                        "jamjet.scheduler.poll-interval-ms=1000",
                        "jamjet.scheduler.max-concurrent-nodes=32",
                        "jamjet.scheduler.max-dispatch-per-tick=16"
                )
                .run(ctx -> {
                    SchedulerConfig config = ctx.getBean(SchedulerConfig.class);
                    assertThat(config.pollInterval().toMillis()).isEqualTo(1000);
                    assertThat(config.maxConcurrentNodesPerExecution()).isEqualTo(32);
                    assertThat(config.maxDispatchPerTick()).isEqualTo(16);
                });
    }
}
