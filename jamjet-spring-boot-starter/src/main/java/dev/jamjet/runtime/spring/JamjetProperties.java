package dev.jamjet.runtime.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the JamJet runtime Spring Boot starter.
 * All properties are prefixed with {@code jamjet}.
 */
@ConfigurationProperties(prefix = "jamjet")
public class JamjetProperties {

    /** Storage backend type. Currently only "in-memory" is supported. */
    private String storage = "in-memory";

    private Scheduler scheduler = new Scheduler();
    private Workers workers = new Workers();
    private Plugins plugins = new Plugins();

    public String getStorage() { return storage; }
    public void setStorage(String storage) { this.storage = storage; }

    public Scheduler getScheduler() { return scheduler; }
    public void setScheduler(Scheduler scheduler) { this.scheduler = scheduler; }

    public Workers getWorkers() { return workers; }
    public void setWorkers(Workers workers) { this.workers = workers; }

    public Plugins getPlugins() { return plugins; }
    public void setPlugins(Plugins plugins) { this.plugins = plugins; }

    public static class Scheduler {
        /** How often (ms) the scheduler polls for runnable nodes. */
        private long pollIntervalMs = 500;
        /** Max concurrent active nodes per execution. */
        private int maxConcurrentNodes = 16;
        /** Max new work items dispatched in a single scheduler tick. */
        private int maxDispatchPerTick = 8;

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

        public int getMaxConcurrentNodes() { return maxConcurrentNodes; }
        public void setMaxConcurrentNodes(int maxConcurrentNodes) { this.maxConcurrentNodes = maxConcurrentNodes; }

        public int getMaxDispatchPerTick() { return maxDispatchPerTick; }
        public void setMaxDispatchPerTick(int maxDispatchPerTick) { this.maxDispatchPerTick = maxDispatchPerTick; }
    }

    public static class Workers {
        /** Number of concurrent workers to spawn. */
        private int concurrency = 4;
        /** Comma-separated queue types these workers poll. */
        private String queueTypes = "general,model,tool";

        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }

        public String getQueueTypes() { return queueTypes; }
        public void setQueueTypes(String queueTypes) { this.queueTypes = queueTypes; }
    }

    public static class Plugins {
        /** Directory to scan for plugin JARs. */
        private String directory = "./plugins";
        /** Whether plugin loading is enabled. */
        private boolean enabled = true;

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
