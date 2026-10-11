# JamJet Java Runtime

Pure-Java agent runtime: checkpointed agent steps, an embeddable workflow engine, and MCP client and server adapters. No sidecar.

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

`replayOrExecute` runs the step the first time and returns the recorded result when the same
checkpoint id is seen again in a replaying `DurabilityContext`. The context keeps results in
memory, so surviving a restart means saving them yourself and loading them into a fresh context
with replay mode on. [`examples/crash-recovery`](examples/crash-recovery/) does this with a
checkpoint file.

---

## Why JamJet

- **Checkpointed steps**: completed steps are returned from the checkpoint cache on replay
  instead of calling the API or LLM again. The cache is in memory; the crash-recovery example
  shows persisting it to a file.
- **Zero overhead** — no REST hops, no sidecar. Runtime lives inside your JVM, shares your
  DataSource, calls your methods directly.
- **Virtual threads**: the worker pool runs work on virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`),
  so tool and model calls can be plain blocking code.
- **Standard Java** — works with Spring AI, LangChain4j, Google ADK, or any Java LLM library.
  JamJet adds durability; you keep your LLM calls.

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

Add the dependency (all modules are on Maven Central at 0.4.0):

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-runtime-instrument</artifactId>
    <version>0.4.0</version>
</dependency>
```

For Spring Boot auto-configuration, use `jamjet-runtime-spring-boot-starter` instead:

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-runtime-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

The starter's only supported storage value is `jamjet.storage=in-memory` (the default). Any other
value logs a warning and falls back to in-memory, so state does not survive a restart.

Then:

1. Annotate your agent class with `@DurableAgent`
2. Annotate each step with `@Checkpoint`
3. Wrap the step body with `DurabilityContext.current().replayOrExecute(id, () -> ...)`

Persisting checkpoints across restarts is up to you, see the crash-recovery example.

---

## Modules

| Module | Description |
|--------|-------------|
| `jamjet-runtime-core` | Framework-agnostic runtime kernel: IR, event store, scheduler, workers, state backends (`InMemoryStateBackend`; `JdbcStateBackend` for H2 and PostgreSQL, not yet wired into the starter or server) |
| `jamjet-runtime-protocols` | MCP client and server adapters, ProtocolAdapter SPI |
| `jamjet-runtime-instrument` | ByteBuddy Java agent, `@DurableAgent`, `@Checkpoint`, `DurabilityContext` |
| `jamjet-runtime-plugins` | ClassLoader-isolated plugin system with ServiceLoader and hot-reload |
| `jamjet-runtime-server` | Standalone Javalin REST server, in-memory storage only |
| `jamjet-runtime-spring-boot-starter` | Spring Boot auto-configuration: embeds the runtime as Spring beans with an in-memory state backend and a REST controller |
| `jamjet-agent` | Governed, durable `Agent` authoring that runs on the Rust engine |
| `jamjet-agent-spring-boot-starter` | Spring Boot starter for `jamjet-agent` (see its README) |
| `jamjet-cloud-sdk` | Drop-in observability for Spring AI / LangChain4j (mirrors Python `jamjet.cloud`) |
| `jamjet-cloud-spring-boot-starter` | Spring Boot auto-config for the Cloud SDK |

---

## JamJet Cloud SDK

Drop-in observability for Spring AI and LangChain4j agents. Mirrors the Python `jamjet.cloud` SDK.

### Spring Boot

```xml
<dependency>
  <groupId>dev.jamjet</groupId>
  <artifactId>jamjet-cloud-spring-boot-starter</artifactId>
  <version>0.4.0</version>
</dependency>
```

```yaml
# application.yml
jamjet:
  cloud:
    api-key: ${JJ_API_KEY}
    project: my-app
```

That's it. Every Spring AI `ChatClient` / `ChatModel` call and every LangChain4j `ChatLanguageModel` call is captured automatically.

### Plain Java (LangChain4j without Spring)

```xml
<dependency>
  <groupId>dev.jamjet</groupId>
  <artifactId>jamjet-cloud-sdk</artifactId>
  <version>0.4.0</version>
</dependency>
```

```java
JamjetCloud.configure(JamjetCloudConfig.builder()
    .apiKey(System.getenv("JJ_API_KEY"))
    .project("my-app")
    .build());

var model = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o-mini")
    .listeners(List.of(new JamjetChatModelListener()))
    .build();
```

---

## License

Apache 2.0
