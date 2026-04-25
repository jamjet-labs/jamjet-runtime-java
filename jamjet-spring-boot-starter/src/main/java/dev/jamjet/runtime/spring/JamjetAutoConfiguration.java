package dev.jamjet.runtime.spring;

import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;
import dev.jamjet.runtime.core.scheduler.Scheduler;
import dev.jamjet.runtime.core.scheduler.SchedulerConfig;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.worker.WorkerGroupConfig;
import dev.jamjet.runtime.core.worker.WorkerPool;
import dev.jamjet.runtime.plugins.PluginLoader;
import dev.jamjet.runtime.plugins.PluginRegistry;
import dev.jamjet.runtime.protocols.ProtocolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Boot auto-configuration for the JamJet runtime.
 * Registers all core beans: state backend, scheduler, worker pool, protocol/plugin registries.
 */
@AutoConfiguration
@EnableConfigurationProperties(JamjetProperties.class)
public class JamjetAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JamjetAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(StateBackend.class)
    public StateBackend stateBackend(JamjetProperties properties) {
        String storage = properties.getStorage();
        if (!"in-memory".equalsIgnoreCase(storage)) {
            log.warn("Unsupported jamjet.storage='{}'; falling back to in-memory", storage);
        }
        log.info("JamJet: using in-memory StateBackend");
        return new InMemoryStateBackend();
    }

    @Bean
    @ConditionalOnMissingBean(NodeExecutorRegistry.class)
    public NodeExecutorRegistry nodeExecutorRegistry() {
        return new NodeExecutorRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(ProtocolRegistry.class)
    public ProtocolRegistry protocolRegistry() {
        return new ProtocolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(PluginRegistry.class)
    public PluginRegistry pluginRegistry() {
        return new PluginRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(PluginLoader.class)
    public PluginLoader pluginLoader(JamjetProperties properties,
                                     NodeExecutorRegistry executorRegistry,
                                     PluginRegistry pluginRegistry) {
        PluginLoader loader = new PluginLoader(
                Paths.get(properties.getPlugins().getDirectory()),
                executorRegistry,
                pluginRegistry
        );
        if (properties.getPlugins().isEnabled()) {
            try {
                loader.scanAndLoad();
            } catch (IOException e) {
                log.warn("JamJet: plugin scan failed (directory may not exist yet): {}", e.getMessage());
            }
        } else {
            log.info("JamJet: plugin loading disabled via jamjet.plugins.enabled=false");
        }
        return loader;
    }

    @Bean
    @ConditionalOnMissingBean(SchedulerConfig.class)
    public SchedulerConfig schedulerConfig(JamjetProperties properties) {
        JamjetProperties.Scheduler s = properties.getScheduler();
        return new SchedulerConfig(
                Duration.ofMillis(s.getPollIntervalMs()),
                s.getMaxConcurrentNodes(),
                s.getMaxDispatchPerTick()
        );
    }

    @Bean
    @ConditionalOnMissingBean(Scheduler.class)
    public Scheduler scheduler(StateBackend backend, SchedulerConfig config) {
        return new Scheduler(backend, config);
    }

    @Bean
    @ConditionalOnMissingBean(WorkerPool.class)
    public WorkerPool workerPool(StateBackend backend,
                                 NodeExecutorRegistry executorRegistry,
                                 JamjetProperties properties) {
        JamjetProperties.Workers w = properties.getWorkers();
        List<String> queueTypes = Arrays.stream(w.getQueueTypes().split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        WorkerPool pool = new WorkerPool(backend, executorRegistry);
        pool.addGroup(new WorkerGroupConfig("jamjet-worker", w.getConcurrency(), queueTypes));
        return pool;
    }

    @Bean
    @ConditionalOnMissingBean(JamjetRestController.class)
    public JamjetRestController jamjetRestController(StateBackend backend) {
        return new JamjetRestController(backend);
    }

    @Bean
    @ConditionalOnMissingBean(DurableAgentBeanPostProcessor.class)
    public DurableAgentBeanPostProcessor durableAgentBeanPostProcessor() {
        return new DurableAgentBeanPostProcessor();
    }
}
