package dev.jamjet.agent.worker;

import dev.jamjet.agent.client.ClaimedWorkItem;
import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.client.JamjetHttpException;
import dev.jamjet.agent.tools.ToolDispatcher;
import dev.jamjet.agent.tools.ToolRegistry;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The durable Java tool-worker: drains the {@code java_tool} queue over the
 * {@link JamjetEngineClient}, dispatching each claimed agent-loop tool turn to the
 * registry-bound {@link ToolDispatcher} and settling the work item back to the engine
 * exactly-once. It is the Java mirror of the hardened Python {@code jamjet worker}
 * loop ({@code jamjet.cli.main._worker_loop}), framework-free plain Java 21.
 *
 * <h2>Lease fencing &mdash; the enforcement axis (M3 + PR #108)</h2>
 * A reclaimed / zombie worker must never complete an item the engine has handed to a
 * new claimant. Two independent guards enforce this:
 * <ol>
 *   <li><b>Proactive heartbeat abort (M3).</b> While a tool runs, a heartbeat task
 *       renews the lease with the claim's {@code lease_fence}. If a heartbeat is
 *       rejected by the engine (any definitive HTTP error response, {@code status >=
 *       400}), the lease is treated as lost: the in-flight dispatch is cancelled
 *       ({@code Future.cancel(true)} interrupts the tool thread) and the worker does
 *       NOT complete. The engine surfaces a stale heartbeat fence as HTTP 500
 *       ({@code FenceLost -> Internal}), so we key the abort on a definitive HTTP
 *       error rather than a specific code; a pure transport error (no HTTP status) is
 *       treated as a transient blip and does not abort (matching the Python worker's
 *       tolerance), because the complete-time fence below is the authoritative
 *       backstop.</li>
 *   <li><b>Authoritative complete-time fence (the backstop).</b> The completion echoes
 *       the same {@code lease_fence}; the engine's {@code /complete} fences on it and
 *       rejects a stale/reclaimed lease with <b>HTTP 409</b>, emitting NO
 *       {@code NodeCompleted}. The worker treats a 409 on complete as a lost-lease
 *       no-op: it does NOT {@code fail} the item (failing would clobber the work the
 *       new claimant is running). This mirrors the hardened Python worker exactly and
 *       guarantees correctness even if the heartbeat abort has not fired yet.</li>
 * </ol>
 *
 * <h2>Registry-gated dispatch &mdash; the RCE gate</h2>
 * The worker never reflects on an attacker-influenceable string. Its reflective
 * surface is a closed set: (a) the single fixed {@link ToolDispatcher} &mdash; the
 * worker verifies the work item's {@code class}/{@code method} payload equals the
 * known dispatch coordinate and fails cleanly otherwise, so a forged {@code java_fn}
 * node naming an arbitrary class can never be {@code Class.forName}'d; and (b) the
 * declared {@code @Tool} methods the dispatcher resolves by name through the
 * {@link ToolRegistry}. No payload or model field becomes a class/method to load.
 */
public final class JavaToolWorker implements AutoCloseable {

    /** The single queue this worker drains. */
    public static final String JAVA_TOOL_QUEUE = "java_tool";

    private static final Logger LOG = System.getLogger(JavaToolWorker.class.getName());
    private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_POLL_BACKOFF = Duration.ofSeconds(2);

    /** The outcome of processing a single claim. */
    public enum ItemResult {
        /** The queue was empty; nothing was claimed. */
        EMPTY,
        /** The tool ran and the completion was accepted by the engine. */
        COMPLETED,
        /** A genuine tool / dispatch / completion error; the item was failed. */
        FAILED,
        /**
         * The item was abandoned without failing it: the lease was lost (heartbeat abort or
         * a 409 on complete), or the worker was interrupted mid-dispatch (shutdown). A no-op,
         * NOT a failure — the engine reclaims the item on lease expiry.
         */
        LOST_LEASE
    }

    private final JamjetEngineClient client;
    private final String workerId;
    private final ToolDispatcher dispatcher;
    private final long heartbeatMillis;
    private final long pollBackoffMillis;

    private final ScheduledExecutorService heartbeats;
    private final ExecutorService dispatchPool;
    private volatile boolean stopped;

    /** Build a worker with default heartbeat (10s) and poll backoff (2s). */
    public JavaToolWorker(JamjetEngineClient client, String workerId, ToolRegistry registry) {
        this(client, workerId, registry, DEFAULT_HEARTBEAT, DEFAULT_POLL_BACKOFF);
    }

    /**
     * @param client            the engine client (work-item protocol)
     * @param workerId          this worker's stable id (echoed on claim + heartbeat)
     * @param registry          the agent's tool registry &mdash; the ONLY callable tools
     * @param heartbeatInterval lease-renewal interval while a tool runs
     * @param pollBackoff       sleep between empty claims in {@link #run()}
     */
    public JavaToolWorker(JamjetEngineClient client, String workerId, ToolRegistry registry,
                          Duration heartbeatInterval, Duration pollBackoff) {
        this.client = Objects.requireNonNull(client, "client");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.dispatcher = new ToolDispatcher(Objects.requireNonNull(registry, "registry"));
        this.heartbeatMillis = heartbeatInterval.toMillis();
        this.pollBackoffMillis = pollBackoff.toMillis();
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jamjet-java-tool-heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.dispatchPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jamjet-java-tool-", 0).factory());
    }

    /** Request a clean stop of {@link #run()} (takes effect at the next loop boundary). */
    public void stop() {
        this.stopped = true;
    }

    /**
     * Claim and process exactly one work item, then return its outcome. Returns
     * {@link ItemResult#EMPTY} when the queue is empty. Mirrors the Python worker's
     * {@code --once} mode.
     */
    public ItemResult runOnce() {
        Optional<ClaimedWorkItem> claimed = client.claimWorkItem(workerId, List.of(JAVA_TOOL_QUEUE));
        if (claimed.isEmpty()) {
            return ItemResult.EMPTY;
        }
        return process(claimed.get());
    }

    /**
     * Continuously claim and process {@code java_tool} items until {@link #stop()} is
     * called or the thread is interrupted, backing off when the queue is empty. A poll
     * error is logged and retried after the backoff (never fatal).
     */
    public void run() {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<ClaimedWorkItem> claimed = client.claimWorkItem(workerId, List.of(JAVA_TOOL_QUEUE));
                if (claimed.isEmpty()) {
                    if (sleep(pollBackoffMillis)) {
                        return; // interrupted
                    }
                    continue;
                }
                process(claimed.get());
            } catch (Exception pollErr) {
                LOG.log(Level.WARNING, () -> "poll error: " + pollErr);
                if (sleep(pollBackoffMillis)) {
                    return;
                }
            }
        }
    }

    // -- per-item processing ----------------------------------------------------

    private ItemResult process(ClaimedWorkItem item) {
        Map<String, Object> payload = item.payload() == null ? Map.of() : item.payload();

        // RCE GATE (dispatch coordinate): the only java_fn coordinate the Agent builder
        // emits is the fixed ToolDispatcher. A payload naming any other class/method is
        // a forged/unknown node: fail cleanly, NEVER Class.forName a payload string.
        // Read the fence before the gate below: a rejection is still a settle, and an
        // unfenced settle emits no NodeFailed, so the node stays scheduled forever.
        long fence = item.leaseFence() == null ? 0L : item.leaseFence();

        String cls = String.valueOf(payload.get("class"));
        String method = String.valueOf(payload.get("method"));
        if (!ToolDispatcher.DISPATCH_CLASS.equals(cls) || !ToolDispatcher.DISPATCH_METHOD.equals(method)) {
            String msg = "unsupported java_fn dispatch coordinate: " + cls + "#" + method
                    + " (only " + ToolDispatcher.DISPATCH_CLASS + "#" + ToolDispatcher.DISPATCH_METHOD + " is callable)";
            LOG.log(Level.WARNING, () -> "rejecting work item " + item.id() + ": " + msg);
            return fail(item, msg, fence);
        }

        Map<String, Object> input = asMap(payload.get("input"));
        AtomicBoolean leaseLost = new AtomicBoolean(false);

        long startNanos = System.nanoTime();
        Future<Map<String, Object>> dispatchFuture = dispatchPool.submit(() -> dispatcher.dispatchToolCalls(input));
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(item, fence, leaseLost, dispatchFuture);
        try {
            Map<String, Object> output;
            try {
                output = dispatchFuture.get();
            } catch (InterruptedException e) {
                // Worker shutdown / cancellation (the WORKER thread was interrupted while
                // waiting) is NOT a tool failure. Restore the interrupt and stop cleanly so
                // the engine reclaims the item on lease expiry — never fail a reclaimable
                // item (the same invariant as a lost lease). Best-effort: stop the in-flight
                // tool so a side-effecting call doesn't keep running after we abandon it.
                Thread.currentThread().interrupt();
                dispatchFuture.cancel(true);
                LOG.log(Level.INFO, () -> "worker interrupted mid-dispatch; abandoning item "
                        + item.id() + " for reclaim (not failing)");
                return ItemResult.LOST_LEASE;
            } catch (CancellationException | ExecutionException e) {
                // Lease-lost gate FIRST: if the heartbeat aborted us (Future.cancel), this is
                // a no-op, never a failure (the new claimant owns the item now).
                if (leaseLost.get()) {
                    LOG.log(Level.INFO, () -> "lease lost mid-dispatch; aborting item " + item.id() + " (not completing)");
                    return ItemResult.LOST_LEASE;
                }
                // A genuine tool / dispatch failure -> fail the item (mirror Python). An
                // ExecutionException here wraps the real tool error.
                Throwable cause = (e instanceof ExecutionException) ? e.getCause() : e;
                String err = cause == null ? String.valueOf(e) : String.valueOf(cause.getMessage() != null ? cause.getMessage() : cause);
                LOG.log(Level.WARNING, () -> "tool dispatch failed for item " + item.id() + ": " + err);
                return fail(item, err, fence);
            }

            // Dispatch succeeded. If the lease was lost during/just after it, do NOT
            // complete (proactive M3 gate; the 409 backstop covers the residual race).
            if (leaseLost.get()) {
                LOG.log(Level.INFO, () -> "lease lost; not completing item " + item.id());
                return ItemResult.LOST_LEASE;
            }

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return complete(item, output, durationMs, fence);
        } finally {
            heartbeat.cancel(true);
        }
    }


    /**
     * Fail the item, threading the lease fence; a 409 is a lost-lease no-op.
     *
     * <p>Mirrors {@link #complete}: the fence makes the engine emit {@code NodeFailed},
     * so the node is retried or dead-lettered instead of staying scheduled forever.
     * A 409 means the lease was reclaimed and a NEW worker owns the item — reporting
     * our failure would kill work that worker is running, so return quietly.
     */
    private ItemResult fail(ClaimedWorkItem item, String error, long fence) {
        try {
            client.failWorkItem(item.id(), error, fence == 0L ? null : fence);
            return ItemResult.FAILED;
        } catch (JamjetHttpException e) {
            if (e.isConflict()) {
                LOG.log(Level.INFO, () -> "failure rejected (409); lease lost for item " + item.id());
                return ItemResult.LOST_LEASE;
            }
            // Anything else: we could not settle either, so the answer is the same —
            // leave the item for lease expiry rather than pretending it failed cleanly.
            // The two arms differ only in log level; both mean "the reclaimer has it".
            LOG.log(Level.WARNING, () -> "could not report failure for item " + item.id() + ": " + e.getMessage());
            return ItemResult.LOST_LEASE;
        }
    }

    /** Settle the item, threading the lease fence; a 409 is a lost-lease no-op. */
    private ItemResult complete(ClaimedWorkItem item, Map<String, Object> output, long durationMs, long fence) {
        // The dispatcher return ({"messages": [...]}) is BOTH the node output and the
        // state_patch: the engine merge-patches it into state (top-level keys replaced),
        // replacing state["messages"] for the next model turn. Identical to the Python
        // python_tool worker (return-as-state_patch).
        String genAiModel = stringOrNull(output.get("gen_ai_model"));
        String finishReason = stringOrNull(output.get("finish_reason"));
        try {
            client.completeWorkItem(
                    item.id(), item.executionId(), item.nodeId(),
                    output, output, durationMs, genAiModel, finishReason,
                    // Echo the claim's fence so the engine fences this completion.
                    fence == 0L ? null : fence,
                    // Echo the claim's key so the engine records the effect against it
                    // and a re-run replays instead of firing the tool a second time.
                    item.idempotencyKey());
            LOG.log(Level.DEBUG, () -> "completed item " + item.id() + " in " + durationMs + "ms");
            return ItemResult.COMPLETED;
        } catch (JamjetHttpException e) {
            if (e.isConflict()) {
                // 409: our fence no longer matches -> the lease was reclaimed and a NEW
                // worker owns this item. Do NOT fail (that would clobber the reclaimed
                // work). Treat the lost lease as a no-op. Mirrors the Python worker.
                LOG.log(Level.INFO, () -> "completion rejected (409); lease lost for item " + item.id());
                return ItemResult.LOST_LEASE;
            }
            // Any other completion error keeps the fail behavior (mirror Python re-raise).
            LOG.log(Level.WARNING, () -> "completion failed for item " + item.id() + ": " + e.getMessage());
            return fail(item, "complete failed: " + e.getMessage(), fence);
        }
    }

    /**
     * Schedule the lease-renewal heartbeat. On a definitive engine rejection
     * ({@code status >= 400}) it flags the lease lost and cancels the in-flight
     * dispatch (M3); a transport-level blip is logged and tolerated.
     */
    private ScheduledFuture<?> scheduleHeartbeat(ClaimedWorkItem item, long fence,
                                                 AtomicBoolean leaseLost, Future<?> dispatchFuture) {
        return heartbeats.scheduleAtFixedRate(() -> {
            if (leaseLost.get()) {
                return;
            }
            try {
                // Echo the claim's fence (null only for a legacy unfenced claim), matching
                // the nullable contract the complete path uses.
                client.heartbeatWorkItem(item.id(), workerId, fence == 0L ? null : fence);
            } catch (JamjetHttpException e) {
                if (e.statusCode() >= 400) {
                    // Definitive engine rejection: the lease is gone (FenceLost -> 500,
                    // or a 4xx). Abort the in-flight tool and stop renewing (M3).
                    LOG.log(Level.WARNING, () -> "heartbeat rejected for item " + item.id()
                            + " (HTTP " + e.statusCode() + "); lease lost -> aborting dispatch");
                    leaseLost.set(true);
                    dispatchFuture.cancel(true);
                } else {
                    // Transport-level blip (no HTTP status): tolerate; the complete-time
                    // fence is the authoritative backstop.
                    LOG.log(Level.WARNING, () -> "heartbeat transport error for item " + item.id() + ": " + e.getMessage());
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, () -> "heartbeat error for item " + item.id() + ": " + e);
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
    }

    // -- helpers ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    /** Sleep, returning true if interrupted (so the caller can exit cleanly). */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    @Override
    public void close() {
        stopped = true;
        heartbeats.shutdownNow();
        dispatchPool.shutdownNow();
    }
}
