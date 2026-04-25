# JamJet Java Runtime

Pure-Java agent runtime. Durable execution, crash recovery, MCP native. No sidecar.

---

## The 10-second demo

```java
@DurableAgent("research-pipeline")
@Service
public class ResearchAgent {

    @Checkpoint("search")
    public String search(String topic) {
        return DurabilityContext.current().replayOrExecute("search", () ->
            searchClient.query(topic)  // real API call
        );
    }

    @Checkpoint("analyze")
    public String analyze(String sources) {
        return DurabilityContext.current().replayOrExecute("analyze", () ->
            llm.chat(SYSTEM_PROMPT, sources)  // real LLM call
        );
    }
}
```

Kill the process after `search` completes. Restart. `analyze` runs. `search` is replayed from
checkpoint — no API call, no charge, instant. Zero code changes.

---

## Why JamJet

- **Crash recovery** — kill your process, restart, resume from last checkpoint. Works with
  any storage backend (H2, Postgres, SQLite).
- **Zero overhead** — no REST hops, no sidecar. Runtime lives inside your JVM, shares your
  DataSource, calls your methods directly.
- **Virtual threads** — 1M concurrent agents on standard blocking code. No `CompletableFuture`
  chains, no reactive operators.
- **Standard Java** — works with Spring AI, LangChain4j, Google ADK, or any Java LLM library.
  JamJet adds durability; you keep your LLM calls.

---

## How it compares

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

## Examples

| Example | Description |
|---------|-------------|
| [`latency-comparison`](examples/latency-comparison/) | Benchmark: embedded runtime vs REST sidecar overhead — p50/p95/p99 latency and 100-concurrent-workflow throughput |
| [`crash-recovery`](examples/crash-recovery/) | Kill the process mid-run, restart, watch completed checkpoints replay instantly |
| [`real-agent`](examples/real-agent/) | Document analysis agent using the OpenAI API — fetch, analyze, summarize with full checkpointing |
| [`spring-boot-comparison`](examples/spring-boot-comparison/) | Side-by-side: old way (Docker + REST sidecar) vs new way (two annotations) |

---

## Quick Start

Add the dependency:

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-runtime-instrument</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For Spring Boot auto-configuration, use `jamjet-spring-boot-starter` instead:

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Then:

1. Annotate your agent class with `@DurableAgent`
2. Annotate each step with `@Checkpoint`
3. Wrap the step body with `DurabilityContext.current().replayOrExecute(id, () -> ...)`

That is the entire integration. No schema changes, no new infrastructure.

---

## Modules

| Module | Description |
|--------|-------------|
| `jamjet-runtime-core` | Framework-agnostic runtime kernel: IR, event store, scheduler, workers, state backends |
| `jamjet-runtime-protocols` | MCP client and server adapters, ProtocolAdapter SPI |
| `jamjet-runtime-instrument` | ByteBuddy Java agent, `@DurableAgent`, `@Checkpoint`, `DurabilityContext` |
| `jamjet-runtime-plugins` | ClassLoader-isolated plugin system with ServiceLoader and hot-reload |
| `jamjet-runtime-server` | Standalone Javalin REST server — same API surface as the Rust runtime |
| `jamjet-spring-boot-starter` | Spring Boot auto-configuration: embeds the runtime as Spring beans |

---

## License

Apache 2.0
