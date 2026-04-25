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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Measures end-to-end latency for a 3-node linear workflow running entirely in-process.
 * Uses InMemoryStateBackend + Scheduler + Worker — no network I/O of any kind.
 *
 * <p>3-node workflow: fetch → transform → store (all StubNodeExecutor, 0ms work)</p>
 */
public class NewPathBenchmark {

    private static final ObjectMapper MAPPER = JamjetJson.shared();
    private static final String WORKFLOW_ID = "benchmark-wf";
    private static final String WORKFLOW_VERSION = "1";

    private StateBackend backend;
    private Scheduler scheduler;
    private NodeExecutorRegistry registry;
    private Worker worker;

    public NewPathBenchmark() throws Exception {
        setup();
    }

    private void setup() throws Exception {
        backend = new InMemoryStateBackend();
        scheduler = new Scheduler(backend, SchedulerConfig.defaults());
        registry = new NodeExecutorRegistry();

        // Register stub executors for all node kinds in the 3-node workflow
        registry.register("tool", new StubNodeExecutor("stub"));

        // Build: fetch -> transform -> store
        WorkflowIr ir = new WorkflowIr(
                WORKFLOW_ID, WORKFLOW_VERSION, "Benchmark Workflow",
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

        JsonNode irJson = MAPPER.valueToTree(ir);
        backend.storeWorkflow(new WorkflowDefinition(WORKFLOW_ID, WORKFLOW_VERSION, irJson, Instant.now()));

        worker = new Worker("benchmark-worker", backend, registry,
                List.of("model", "tool", "python_tool", "retrieval", "general"));
    }

    /**
     * Runs the benchmark.
     *
     * @param warmup     number of warmup iterations (not measured)
     * @param iterations number of measured iterations
     * @return list of per-workflow latency samples in nanoseconds
     */
    public List<Long> run(int warmup, int iterations) throws Exception {
        // Warmup phase
        for (int i = 0; i < warmup; i++) {
            runSingleWorkflow();
        }

        // Measured phase
        List<Long> samples = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            runSingleWorkflow();
            long elapsed = System.nanoTime() - start;
            samples.add(elapsed);
        }
        return samples;
    }

    /**
     * Runs a single 3-node workflow synchronously to completion.
     * Tick 1: scheduler dispatches "fetch", worker executes.
     * Tick 2: scheduler dispatches "transform", worker executes.
     * Tick 3: scheduler dispatches "store", worker executes.
     */
    private void runSingleWorkflow() throws Exception {
        ExecutionId execId = ExecutionId.create();
        ObjectNode initialInput = MAPPER.createObjectNode();

        backend.createExecution(new WorkflowExecution(
                execId, WORKFLOW_ID, WORKFLOW_VERSION, WorkflowStatus.RUNNING,
                initialInput, initialInput, Instant.now(), Instant.now(), null,
                SessionType.STATELESS
        ));
        backend.appendEvent(Event.create(execId, 1,
                new EventKind.WorkflowStarted(WORKFLOW_ID, WORKFLOW_VERSION, initialInput)));

        // 3 serial scheduler-tick + worker-execute cycles
        for (int i = 0; i < 3; i++) {
            scheduler.tick();
            worker.pollAndExecute();
        }
    }
}
