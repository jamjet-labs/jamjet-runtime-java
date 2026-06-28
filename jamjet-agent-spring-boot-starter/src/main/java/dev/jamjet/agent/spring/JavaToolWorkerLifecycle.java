package dev.jamjet.agent.spring;

import dev.jamjet.agent.worker.JavaToolWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Binds a {@link JavaToolWorker}'s blocking drain loop to the Spring context lifecycle:
 * {@link #start()} spawns the worker on a daemon thread when the context starts, and
 * {@link #stop()} stops it cleanly on context shutdown.
 *
 * <p>{@link JavaToolWorker#run()} is a blocking poll loop, so it must NOT run on the
 * context-refresh thread; this wrapper runs it on a dedicated daemon thread and never
 * blocks application startup. It inherits the {@link SmartLifecycle} defaults
 * ({@code isAutoStartup() == true}, phase {@link SmartLifecycle#DEFAULT_PHASE} = start
 * last / stop first), which is what a background worker wants: it starts after all the
 * other beans are ready and stops before they tear down.
 */
public class JavaToolWorkerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JavaToolWorkerLifecycle.class);

    /** Bound on how long {@link #stop()} waits for the worker thread to exit. */
    private static final long STOP_JOIN_MILLIS = 5_000;

    private final JavaToolWorker worker;
    private final String threadName;

    private volatile boolean running;
    private Thread thread;

    public JavaToolWorkerLifecycle(JavaToolWorker worker, String threadName) {
        this.worker = worker;
        this.threadName = threadName;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        thread = new Thread(worker::run, threadName);
        thread.setDaemon(true);
        thread.start();
        running = true;
        log.info("JamJet: started java_tool worker thread '{}'", threadName);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        // Ask the loop to stop at its next boundary, interrupt to break the poll-backoff
        // sleep / in-flight claim, then close to shut the heartbeat + dispatch pools.
        worker.stop();
        if (thread != null) {
            thread.interrupt();
        }
        worker.close();
        if (thread != null) {
            try {
                thread.join(STOP_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        log.info("JamJet: stopped java_tool worker thread '{}'", threadName);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
