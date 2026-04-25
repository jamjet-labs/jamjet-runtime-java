package dev.jamjet.examples.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.SessionType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;
import dev.jamjet.runtime.core.ir.*;
import dev.jamjet.runtime.core.scheduler.Scheduler;
import dev.jamjet.runtime.core.scheduler.SchedulerConfig;
import dev.jamjet.runtime.core.state.*;
import dev.jamjet.runtime.core.worker.Worker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures throughput when running 100 concurrent 3-node workflows on both
 * the embedded path and the REST sidecar path.
 *
 * <p>Virtual threads are used for both paths so neither is penalized by
 * thread-pool overhead.</p>
 */
public class ConcurrencyBenchmark {

    private static final ObjectMapper MAPPER = JamjetJson.shared();
    private static final String WORKFLOW_ID = "concurrency-wf";
    private static final String WORKFLOW_VERSION = "1";

    /**
     * Result of a concurrency benchmark run.
     *
     * @param total     number of workflows attempted
     * @param completed number that completed without error
     * @param totalMs   wall-clock time in milliseconds for all workflows to finish
     * @param avgMs     average per-workflow latency
     */
    public record ConcurrencyResult(int total, int completed, long totalMs, double avgMs) {
    }

    // ── Embedded path ──────────────────────────────────────────────────────────

    /**
     * Runs {@code count} concurrent 3-node workflows in-process using virtual threads.
     */
    public ConcurrencyResult runEmbedded(int count) throws Exception {
        StateBackend backend = new InMemoryStateBackend();
        Scheduler scheduler = new Scheduler(backend, SchedulerConfig.defaults());
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.register("tool", new StubNodeExecutor("stub"));

        WorkflowIr ir = buildIr();
        JsonNode irJson = MAPPER.valueToTree(ir);
        backend.storeWorkflow(new WorkflowDefinition(WORKFLOW_ID, WORKFLOW_VERSION, irJson, Instant.now()));

        Worker worker = new Worker("concurrency-worker", backend, registry,
                List.of("model", "tool", "python_tool", "retrieval", "general"));

        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger completed = new AtomicInteger(0);

        long start = System.currentTimeMillis();

        // Submit all workflow creations via virtual threads
        List<Thread> threads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    ExecutionId execId = ExecutionId.create();
                    ObjectNode initialInput = MAPPER.createObjectNode();
                    backend.createExecution(new WorkflowExecution(
                            execId, WORKFLOW_ID, WORKFLOW_VERSION, WorkflowStatus.RUNNING,
                            initialInput, initialInput, Instant.now(), Instant.now(), null,
                            SessionType.STATELESS
                    ));
                    backend.appendEvent(Event.create(execId, 1,
                            new EventKind.WorkflowStarted(WORKFLOW_ID, WORKFLOW_VERSION, initialInput)));
                    completed.incrementAndGet();
                } catch (Exception e) {
                    // workflow creation failed — not counted
                } finally {
                    latch.countDown();
                }
            });
            threads.add(t);
        }
        latch.await();

        // Drive all workflows to completion: each needs 3 scheduler ticks + 3 worker executions
        // We do this serially per-tick to avoid race conditions in the in-memory backend
        for (int tick = 0; tick < 3; tick++) {
            scheduler.tick();
            // Each tick can produce up to `count` work items — drain them all
            boolean claimed = true;
            while (claimed) {
                claimed = worker.pollAndExecute();
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        int total = count;
        double avg = total > 0 ? (double) elapsed / total : 0;
        return new ConcurrencyResult(total, completed.get(), elapsed, avg);
    }

    // ── REST sidecar path ──────────────────────────────────────────────────────

    /**
     * Runs {@code count} concurrent 3-node workflows via HTTP to WireMock using virtual threads.
     */
    public ConcurrencyResult runRest(OldPathBenchmark restBenchmark, int count)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger completed = new AtomicInteger(0);
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = restBenchmark.getBaseUrl();

        long start = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    simulateRestWorkflow(client, baseUrl);
                    completed.incrementAndGet();
                } catch (Exception e) {
                    // failed — not counted as completed
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        long elapsed = System.currentTimeMillis() - start;
        double avg = count > 0 ? (double) elapsed / count : 0;
        return new ConcurrencyResult(count, completed.get(), elapsed, avg);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void simulateRestWorkflow(HttpClient client, String baseUrl)
            throws IOException, InterruptedException {
        httpPost(client, baseUrl + "/workflows", "{}");
        httpPost(client, baseUrl + "/executions", "{}");
        for (int node = 0; node < 3; node++) {
            String itemId = UUID.randomUUID().toString();
            httpPost(client, baseUrl + "/work-items", "{}");
            httpPost(client, baseUrl + "/work-items/claim", "{}");
            httpPost(client, baseUrl + "/work-items/" + itemId + "/complete", "{}");
        }
    }

    private void httpPost(HttpClient client, String url, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private WorkflowIr buildIr() {
        return new WorkflowIr(
                WORKFLOW_ID, WORKFLOW_VERSION, "Concurrency Benchmark Workflow",
                null, null, "fetch",
                Map.of(
                        "fetch",
                        new NodeDef("fetch", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null),
                        "transform",
                        new NodeDef("transform", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null),
                        "store",
                        new NodeDef("store", new NodeKind.Tool(null, Map.of(), null), null, null, null, Map.of(), null, null)
                ),
                List.of(
                        new EdgeDef("fetch", "transform", null),
                        new EdgeDef("transform", "store", null)
                ),
                null, null, null, null, null, null, null, null, null, null, null, null
        );
    }
}
