# Zero-Sidecar Durable AI Agents in Java

Enterprise Java teams building multi-step AI agents face a durability problem that most
frameworks do not address.

---

## The Problem

A multi-step agent — search the web, call an LLM, write results to a database — is stateful.
If the JVM crashes or is killed between steps, the work in progress is lost. The next run starts
from scratch, making the same LLM API calls, burning the same token budget, taking the same
wall-clock time.

The existing solutions are unsatisfying:

**Option 1: Accept the loss.** Spring AI and LangChain4j are excellent libraries, but neither
provides durable execution. A crash means a full retry. For a 10-step agent with an LLM call at
each step, that is potentially 10x the API spend and 10x the latency.

**Option 2: Bolt on a sidecar.** The Rust-based JamJet runtime solves durability, but it runs
as a separate process. Every checkpoint becomes a REST call. Your Spring service depends on
Docker being up. CI needs the sidecar running before tests start. Ops complexity grows linearly
with agent complexity.

Neither option is acceptable for production-grade agents.

---

## The Solution

```java
@DurableAgent("research-pipeline")
@Service
public class ResearchAgent {

    @Checkpoint("search")
    public String search(String topic) {
        return DurabilityContext.current().replayOrExecute("search", () ->
            searchClient.query(topic)
        );
    }

    @Checkpoint("analyze")
    public String analyze(String sources) {
        return DurabilityContext.current().replayOrExecute("analyze", () ->
            llm.chat(SYSTEM_PROMPT, sources)
        );
    }

    @Checkpoint("synthesize")
    public String synthesize(String analysis) {
        return DurabilityContext.current().replayOrExecute("synthesize", () ->
            llm.chat(SUMMARY_PROMPT, analysis)
        );
    }
}
```

Add one dependency. Annotate your agent class. Annotate your steps. Done.

The runtime lives inside your JVM. No REST hops. No Docker. No sidecar. If the process crashes
after `search` completes, a restart replays `search` from the checkpoint store (an in-process
H2 database by default) and continues from `analyze`.

---

## How It Works

### Event Sourcing

Every `replayOrExecute` call either executes the lambda and appends its return value to an
append-only event log, or — if the execution ID already exists in the log — returns the stored
value without executing the lambda.

This is the same algorithm used in the Rust JamJet runtime. The event log is the source of
truth. Recovery is deterministic: replay the log in order, skip any step whose ID is already
recorded, execute the rest.

The default backend is H2 (embedded, zero-config). Swap in Postgres with a single configuration
property. The schema is three tables: `agent_runs`, `checkpoint_events`, and `checkpoint_results`.
It migrates automatically via Flyway.

```java
// On first run: executes and records
String result = ctx.replayOrExecute("search", () -> searchClient.query(topic));

// On recovery: returns stored value instantly, lambda never called
String result = ctx.replayOrExecute("search", () -> searchClient.query(topic));
```

The lambda is the unit of idempotency. The ID is the checkpoint name. Same name, same result.

### ByteBuddy Instrumentation

`@DurableAgent` and `@Checkpoint` are pure marker annotations. At class load time, the JamJet
Java agent (a standard `-javaagent` JVM flag) uses ByteBuddy to transform any class annotated
with `@DurableAgent`. Each method annotated with `@Checkpoint` is wrapped: the method body is
replaced with a `replayOrExecute` call using the method's checkpoint ID.

This means you can choose your level of explicitness:

- **Explicit API** (no JVM agent required): call `DurabilityContext.current().replayOrExecute`
  directly, as shown in all examples in this repository. This works in any test runner,
  any CI environment, without adding `-javaagent` to the JVM flags.

- **Annotation-driven** (JVM agent required): annotate and let ByteBuddy handle the wiring.
  The annotation approach is cleaner for production code where checkpoint IDs are stable.

The Spring Boot starter registers a `BeanPostProcessor` that detects `@DurableAgent` beans at
startup and logs them. If the JVM agent is not attached, the annotations are still valid —
they serve as documentation and as hooks for future tooling.

### Virtual Threads

Every agent run executes on its own virtual thread (Java 21 `Thread.ofVirtual()`). There are
no thread pools to tune. No `CompletableFuture` chains. No reactive operators. Write blocking
code — `Thread.sleep`, JDBC, `HttpClient.send` — and the JVM scheduler handles concurrency.

The `examples/latency-comparison` benchmark runs 100 simultaneous 3-node workflows. With
virtual threads, all 100 run concurrently on a small carrier thread pool. The latency is
dominated by the work being done, not by scheduling overhead.

---

## Benchmarks

The `examples/latency-comparison` module measures two things:

**Phase 1 — Single workflow latency** (1,000 iterations, 100 warmup): in-process method calls
vs REST calls to a WireMock stub (representative of the sidecar path).

**Phase 2 — Concurrent throughput** (100 simultaneous 3-node workflows): total wall time and
average per-workflow time on both paths.

Expected output format (run `mvn compile exec:java` in `examples/latency-comparison` to see
your numbers):

```
╔══════════════════════════════════════════════════════════╗
║     JamJet Runtime Benchmark — 3-node workflow          ║
╚══════════════════════════════════════════════════════════╝

Phase 1: Single Workflow Latency (1000 iterations)
  Warming up...

                             p50      p95      p99
Embedded runtime           0.05ms   0.12ms   0.31ms
REST sidecar               1.83ms   3.17ms   5.42ms
Overhead saved               97%      96%      94%

Phase 2: Concurrent Throughput (100 simultaneous workflows)
Embedded:  100/100 completed in 12ms   (avg 0.12ms/workflow)
REST:      100/100 completed in 198ms  (avg 1.98ms/workflow)
Speedup:   16.5x
```

These are representative numbers on a developer laptop (M-series MacBook). Numbers vary by
machine, JVM startup state, and GC pressure. The key point is the shape of the result: the
embedded path eliminates serialization, TCP stack traversal, and HTTP parsing from every
checkpoint operation. That overhead is small per step but compounds across a 10-step agent.

LLM latency dominates in production — a GPT-4o call takes 800ms to 4s, dwarfing any runtime
overhead. But checkpoint overhead is additive across every step in the pipeline. A 20-step
agent with 2ms overhead per checkpoint adds 40ms of pure scheduling noise.

---

## Crash Recovery Demo

`examples/crash-recovery` demonstrates the recovery path without requiring a real LLM. The
`ResearchAgent` has three steps: `search`, `analyze`, `synthesize`. The `CrashSimulator` kills
the process after `search` completes.

**Run phase 1 (crash after search):**

```bash
cd examples/crash-recovery
mvn compile exec:java
```

Expected output:

```
=== Phase 1: Running with crash simulation ===
[1/3] Executing search for "quantum computing"...     checkpointed (503ms)
Simulating crash after search checkpoint...

Process terminated. Checkpoint saved to: /tmp/jamjet-crash-demo/run-abc123.json
```

**Restart (recovery run):**

```bash
mvn compile exec:java -Djamjet.recovery=true
```

Expected output:

```
=== Phase 2: Recovering from checkpoint ===
[1/3] Replaying search from checkpoint...             skipped (0ms)
[2/3] Executing analyze...                            checkpointed (501ms)
[3/3] Executing synthesize...                         completed (498ms)

Recovery complete. Result: Final report: Analysis complete. Key finding: quantum advantage confirmed.
```

`search` took 0ms the second time. The LLM call (or in this case, the simulated work) did not
run. The checkpoint store returned the stored result immediately.

Kill your process mid-agent. Restart. It picks up where it left off. No special handling in
your agent code.

---

## Migration Guides

JamJet is not asking you to rewrite. It is asking you to add two annotations and one method
call per step.

### From Spring AI

Spring AI's `ChatClient` is unchanged. You keep your prompt templates, your tools, your
advisors. You add the JamJet layer around the LLM calls that need durability.

**Before:**

```java
@Service
public class SummarizationAgent {

    private final ChatClient chat;

    public String summarize(String document) {
        return chat.prompt()
            .user(document)
            .call()
            .content();
    }
}
```

**After:**

```java
@DurableAgent("summarization")
@Service
public class SummarizationAgent {

    private final ChatClient chat;

    @Checkpoint("summarize")
    public String summarize(String document) {
        return DurabilityContext.current().replayOrExecute("summarize", () ->
            chat.prompt()
                .user(document)
                .call()
                .content()
        );
    }
}
```

The `ChatClient` bean is injected by Spring AI as before. The `DurabilityContext` is managed
by the JamJet Spring Boot starter. If the JVM is killed between calls, a restart replays any
completed checkpoints and continues from where it stopped.

### From LangChain4j

LangChain4j's `@AiService` generates an implementation from an interface. That generated class
is not easily annotated. The clean migration path is to keep the `@AiService` interface and
wrap it in a durable orchestrator class.

**Before:**

```java
interface ResearchAssistant {
    String search(String topic);
    String analyze(String sources);
}

@Service
public class ResearchOrchestrator {

    @Autowired
    private ResearchAssistant assistant;

    public String run(String topic) {
        String sources = assistant.search(topic);
        return assistant.analyze(sources);
    }
}
```

**After:**

```java
// ResearchAssistant @AiService interface unchanged

@DurableAgent("research")
@Service
public class ResearchOrchestrator {

    @Autowired
    private ResearchAssistant assistant;

    public String run(String topic) {
        String sources = checkpoint("search", () -> assistant.search(topic));
        return checkpoint("analyze", () -> assistant.analyze(sources));
    }

    private <T> T checkpoint(String id, Supplier<T> fn) {
        return DurabilityContext.current().replayOrExecute(id, fn);
    }
}
```

The `@AiService`-generated proxy is unchanged. The orchestrator adds durability around the
calls to it. You do not need to touch the LangChain4j interface definition.

### From Google ADK Java

Google ADK's `AgentContext` and Gemini client calls are unchanged. Wrap the individual LLM
calls with `replayOrExecute`.

**Before:**

```java
public class GeminiPipelineAgent {

    private final GenerativeModel model;

    public String analyze(String document) {
        GenerateContentResponse r = model.generateContent(document);
        return r.getCandidates(0).getContent().getParts(0).getText();
    }
}
```

**After:**

```java
@DurableAgent("gemini-pipeline")
public class GeminiPipelineAgent {

    private final GenerativeModel model;

    @Checkpoint("analyze")
    public String analyze(String document) {
        return DurabilityContext.current().replayOrExecute("analyze", () -> {
            GenerateContentResponse r = model.generateContent(document);
            return r.getCandidates(0).getContent().getParts(0).getText();
        });
    }
}
```

The Gemini client call is inside the lambda. On first run it executes and the result is stored.
On recovery, the lambda is skipped and the stored string is returned.

---

## Comparison

| Feature                    | JamJet              | Spring AI   | LangChain4j | Koog        | Google ADK  |
|----------------------------|---------------------|-------------|-------------|-------------|-------------|
| Durable execution          | Yes (event sourced) | No          | No          | No          | No          |
| Crash recovery             | Checkpoint-level    | No          | No          | No          | No          |
| Virtual threads            | Native              | No          | No          | Coroutines  | No          |
| Plugin hot-reload          | ClassLoader isolated| No          | No          | No          | No          |
| MCP native                 | Client + Server     | Client only | Client only | No          | Client only |
| Bytecode instrumentation   | @DurableAgent       | No          | No          | No          | No          |
| Sidecar required           | No                  | N/A         | N/A         | N/A         | N/A         |

---

## Getting Started

Add the dependency (Spring Boot):

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Or without Spring (instrument module only):

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-runtime-instrument</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

No additional infrastructure required. The default backend is an embedded H2 database. For
Postgres, set `jamjet.datasource.url` to your JDBC URL — the starter picks it up automatically
and runs Flyway migrations on startup.

For a complete working example with real OpenAI API calls, start with
[`examples/real-agent`](../../examples/real-agent/). It runs with one environment variable
(`OPENAI_API_KEY`) and no other infrastructure. The agent fetches a URL, calls GPT-4o-mini
twice (analyze then summarize), and produces structured JSON output — all three steps
checkpointed.

For a walkthrough of crash recovery without any external dependencies, see
[`examples/crash-recovery`](../../examples/crash-recovery/). No API key needed: the steps
simulate work with `Thread.sleep` so you can observe the checkpoint and replay behavior in
isolation.

For a side-by-side diff of the old sidecar pattern vs the new embedded pattern, see
[`examples/spring-boot-comparison`](../../examples/spring-boot-comparison/). The `old-way`
module makes REST calls to a sidecar URL; the `new-way` module uses `@DurableAgent` with the
same three steps and no HTTP client.

Full documentation: [docs.jamjet.dev](https://docs.jamjet.dev)
