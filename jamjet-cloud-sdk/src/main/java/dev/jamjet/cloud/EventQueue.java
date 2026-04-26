package dev.jamjet.cloud;

import dev.jamjet.cloud.http.CloudHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe queue with background batched flushing. Mirrors the Python
 * SDK's EventQueue: same flush interval / size, same fail-open behavior,
 * same circuit-breaker after consecutive failures.
 */
final class EventQueue {

    private static final Logger LOG = LoggerFactory.getLogger(EventQueue.class);
    private static final int CIRCUIT_OPEN_AFTER = 5;

    private final CloudHttpClient client;
    private final long flushIntervalMs;
    private final int flushSize;
    private final ConcurrentLinkedDeque<Map<String, Object>> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private ScheduledExecutorService scheduler;

    EventQueue(CloudHttpClient client, long flushIntervalMs, int flushSize) {
        this.client = client;
        this.flushIntervalMs = flushIntervalMs;
        this.flushSize = flushSize;
    }

    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jamjet-cloud-flusher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        // Final flush on JVM shutdown.
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "jamjet-cloud-shutdown"));
    }

    void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    void push(Map<String, Object> event) {
        queue.addLast(event);
        if (queue.size() >= flushSize) {
            flush();
        }
    }

    private void flush() {
        if (queue.isEmpty()) return;
        if (consecutiveFailures.get() >= CIRCUIT_OPEN_AFTER) {
            // Circuit breaker open — drain to avoid unbounded growth.
            queue.clear();
            return;
        }

        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, Object> e;
        while ((e = queue.pollFirst()) != null && batch.size() < 1000) {
            batch.add(e);
        }
        if (batch.isEmpty()) return;

        try {
            client.postEvents(batch);
            consecutiveFailures.set(0);
        } catch (Exception ex) {
            LOG.debug("event flush failed: {}", ex.toString());
            int failures = consecutiveFailures.incrementAndGet();
            if (failures < CIRCUIT_OPEN_AFTER) {
                // Re-queue at the front for retry on the next tick.
                for (int i = batch.size() - 1; i >= 0; i--) {
                    queue.addFirst(batch.get(i));
                }
            }
            // else: drop, circuit will reset on next successful send.
        }
    }
}
