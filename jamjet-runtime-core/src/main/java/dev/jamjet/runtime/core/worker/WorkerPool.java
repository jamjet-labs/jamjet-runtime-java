package dev.jamjet.runtime.core.worker;

import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;
import dev.jamjet.runtime.core.state.StateBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages groups of {@link Worker}s on virtual threads.
 *
 * <p>Each {@link WorkerGroupConfig} defines a prefix, concurrency, and queue types.
 * On {@link #start()}, the pool spawns virtual threads for all configured workers.</p>
 */
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final StateBackend backend;
    private final NodeExecutorRegistry executorRegistry;
    private final List<WorkerGroupConfig> groups = new ArrayList<>();
    private final Duration pollInterval;
    private final List<Worker> workers = new ArrayList<>();
    private ExecutorService executor;

    public WorkerPool(StateBackend backend, NodeExecutorRegistry executorRegistry) {
        this(backend, executorRegistry, Duration.ofMillis(500));
    }

    public WorkerPool(StateBackend backend, NodeExecutorRegistry executorRegistry, Duration pollInterval) {
        this.backend = backend;
        this.executorRegistry = executorRegistry;
        this.pollInterval = pollInterval;
    }

    /**
     * Adds a worker group configuration. Must be called before {@link #start()}.
     *
     * @param config the group configuration
     * @return this pool for chaining
     */
    public WorkerPool addGroup(WorkerGroupConfig config) {
        groups.add(config);
        return this;
    }

    /**
     * Starts all configured worker groups on virtual threads.
     * Creates {@code concurrency} workers per group, each with an ID like "{prefix}-0".
     */
    public void start() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        for (WorkerGroupConfig group : groups) {
            for (int i = 0; i < group.concurrency(); i++) {
                String workerId = group.idPrefix() + "-" + i;
                Worker worker = new Worker(workerId, backend, executorRegistry,
                        group.queueTypes(), pollInterval);
                workers.add(worker);
                executor.submit(worker);
            }
        }
        log.info("WorkerPool started with {} workers across {} groups",
                workers.size(), groups.size());
    }

    /**
     * Stops all workers and shuts down the executor with a 5-second timeout.
     */
    public void stop() {
        for (Worker worker : workers) {
            worker.stop();
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    log.warn("WorkerPool executor did not terminate within 5 seconds, forced shutdown");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("WorkerPool stopped ({} workers)", workers.size());
    }

    /**
     * Returns the total number of workers that have been started.
     */
    public int workerCount() {
        return workers.size();
    }
}
