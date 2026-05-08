# Kotlin + Koog + Engram + JamJet Cloud Demo

A multi-turn chat agent built on **[Koog](https://github.com/JetBrains/koog) 0.8** (JetBrains' Kotlin agent framework) that **remembers facts across calls** via [Engram](https://github.com/jamjet-labs/jamjet/tree/main/runtime/engram-server) and is **observed end-to-end** by [JamJet Cloud](https://cloud.jamjet.dev) — drop in a single ~25-line extension function and your Koog agent is shipping OTLP traces.

## What this demo shows

- **Koog 0.8** Kotlin-native agent — `AIAgent` constructor with OpenAI executor, `singleRunStrategy`, and a `ToolRegistry`
- **`koog-spring-boot-starter`** autoconfigures the OpenAI `SingleLLMPromptExecutor` from `ai.koog.openai.api-key`
- **`engram-spring-boot-starter`** autoconfigures `EngramClient` so the agent's `@Tool` methods record + recall facts against a real Engram server
- **One extension function — `addJamjetCloudExporter()`** — wires Koog's built-in OpenTelemetry feature to JamJet Cloud's OTLP/HTTP-protobuf intake. Uses the stock `OtlpHttpSpanExporter` already on Koog's classpath; no custom marshaler, no proprietary SDK.

## The headline file: `JamjetCloudExporter.kt`

The entire JamJet integration is one extension on Koog's `OpenTelemetryConfig`:

```kotlin
@JvmOverloads
public fun OpenTelemetryConfig.addJamjetCloudExporter(
    apiKey: String? = null,
    apiUrl: String = "https://api.jamjet.dev",
    timeout: Duration = Duration.ofSeconds(10),
) {
    val key = apiKey
        ?: System.getenv("JAMJET_API_KEY")
        ?: error("JAMJET_API_KEY is missing.")

    addSpanExporter(
        OtlpHttpSpanExporter.builder()
            .setEndpoint("$apiUrl/v1/otlp/v1/traces")
            .addHeader("Authorization", "Bearer $key")
            .setTimeout(timeout)
            .build()
    )
}
```

That's it. Mirrors the `addDatadogExporter` / `addLangfuseExporter` pattern that ships with Koog — plug it into any Koog agent's `install(OpenTelemetry) { ... }` block and JamJet receives every LLM span, tool span, and cost rollup. The exporter is the standard `io.opentelemetry:opentelemetry-exporter-otlp` already pulled in by `agents-features-opentelemetry-jvm:0.8.0`.

## How it's wired

```
User → POST /chat?session=alice ──→ Koog AIAgent
                                        │
                                        ├─→ OpenAI chat completion (via SingleLLMPromptExecutor)
                                        │
                                        ├─→ MemoryTools.rememberFact / recallFacts (Koog @Tool)
                                        │       │
                                        │       └─→ EngramClient ──→ Engram REST API (Docker)
                                        │
                                        └─→ OpenTelemetry feature → addJamjetCloudExporter()
                                                                          │
                                                                          └─→ OTLP/HTTP-protobuf → JamJet Cloud
```

JamJet Cloud's OTLP intake (`POST /v1/otlp/v1/traces`) ingests Koog's spans directly — no per-framework adapter, no proprietary SDK on the agent side.

## Prerequisites

- **Java 21+**
- **Docker Desktop** (or any Docker engine) — for the Engram sidecar
- **An OpenAI API key** — sign up at [platform.openai.com](https://platform.openai.com/api-keys)
- **A JamJet Cloud project** — sign up at [cloud.jamjet.dev](https://cloud.jamjet.dev), create a project, copy the API key

> **Cost note:** The OpenAI key is used twice per chat turn — once by Koog for the chat completion, once by Engram for fact extraction. Both calls use `gpt-4o-mini` by default.

## Run it

```bash
git clone https://github.com/jamjet-labs/jamjet-runtime-java
cd jamjet-runtime-java/examples/kotlin-koog-engram-cloud-demo

cp .env.example .env             # Windows: copy .env.example .env
# Edit .env — paste your OPENAI_API_KEY and JAMJET_API_KEY

docker compose up -d             # boots Engram on 127.0.0.1:9090
./mvnw spring-boot:run           # Windows: mvnw.cmd spring-boot:run
```

The app starts on `127.0.0.1:8181` (8181 instead of 8080 to leave room for a local JamJet Cloud dev stack on 8080). `PreflightCheck` validates both env vars and waits for Engram's `/health` endpoint before the server accepts requests — if either is missing or Engram is unreachable, the app exits with a clear error.

In another terminal:

```bash
# Tell the agent a fact — it stores it in Engram
curl -s -X POST "localhost:8181/chat?session=alice" \
  -H "Content-Type: text/plain" \
  -d "I work at Acme as a Kotlin engineer"
# → {"session":"alice","reply":"Got it, I've stored that you work at Acme as a Kotlin engineer."}

# Ask about it — agent recalls from Engram
curl -s -X POST "localhost:8181/chat?session=alice" \
  -H "Content-Type: text/plain" \
  -d "Where do I work?"
# → {"session":"alice","reply":"You work at Acme."}

curl -s -X POST "localhost:8181/chat?session=alice" \
  -H "Content-Type: text/plain" \
  -d "What languages do I use?"
# → {"session":"alice","reply":"Based on what you've shared, you work as a Kotlin engineer."}
```

Each response is a JSON object `{"session": "...", "reply": "..."}`.

## See the trace in JamJet Cloud

Open [cloud.jamjet.dev/dashboard/graph](https://cloud.jamjet.dev/dashboard/graph) — each `/chat` call appears as a trace tagged `service.name=kotlin-koog-engram-demo` with:

- 1 `invoke_agent` span
- 1+ inference spans (OpenAI chat completion)
- 1+ tool execution spans (`rememberFact` or `recallFacts`)
- Cost rollup (per-token, per-call)

## Anatomy

The interesting code is ~50 LOC across 5 Kotlin files:

| File | What it does |
|---|---|
| `cloud/JamjetCloudExporter.kt` | The ~25-line extension function — adds JamJet to any Koog agent's `OpenTelemetry` config |
| `MemoryTools.kt` | `ToolSet` with `@Tool`+`@LLMDescription` methods backed by autoconfigured `EngramClient` |
| `MemoryAgent.kt` | Builds a Koog `AIAgent` per request: OpenAI executor + tools + `install(OpenTelemetry)` |
| `ChatController.kt` | `POST /chat?session=X` — accepts `text/plain`, returns `{"session","reply"}` |
| `startup/PreflightCheck.kt` | Validates env vars + polls Engram `/health` before the app accepts traffic |

The pom has three Maven dependencies for the agent stack: `koog-spring-boot-starter`, `agents-features-opentelemetry-jvm`, `engram-spring-boot-starter`.

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `engram.base-url` | `http://127.0.0.1:9090` | Where the autoconfigured `EngramClient` connects |
| `ai.koog.openai.api-key` | `${OPENAI_API_KEY}` | Koog OpenAI client API key |
| `jamjet.cloud.api-key` | `${JAMJET_API_KEY}` | JamJet Cloud project key (passed to `addJamjetCloudExporter`) |
| `jamjet.cloud.api-url` | `https://api.jamjet.dev` | JamJet Cloud OTLP intake URL |

To swap the chat model (e.g. to `OpenAIModels.Chat.GPT4o`), edit `MemoryAgent.kt`. To use a different LLM provider for Engram's fact extraction, change `ENGRAM_LLM_PROVIDER` in `docker-compose.yml`.

## How is this different from Track 1's Java/Spring AI demo?

[Track 1 (`spring-ai-engram-cloud-demo`)](../spring-ai-engram-cloud-demo) targets Spring AI users — observability is wired through Spring Boot's standard OTLP tracing autoconfig (Micrometer Observation → OTel span → OTLP exporter, all in `application.yml`). This Kotlin track targets Koog users — observability is wired through Koog's built-in OpenTelemetry feature via the same stock `OtlpHttpSpanExporter`. **JamJet Cloud sees both flavours of trace identically** (same `service.name`, same span shape, same cost rollups) because both end up at the OTLP intake. Pick the demo that matches your runtime.

## Windows notes

- Use PowerShell or cmd; `mvnw.cmd` is the entry point instead of `./mvnw`.
- WSL2 users: run from the WSL side for cleanest networking with Docker Desktop.
- The `ghcr.io/jamjet-labs/engram-server:0.5.0` image is multi-arch.

## Cleaning up

```bash
docker compose down              # stops Engram, removes container + volume
# Press Ctrl-C on the Spring app
```

When you're done, rotate or delete your JamJet API key and OpenAI key in their respective dashboards.

## Security

- `.env` is in `.gitignore` — never commit your keys.
- Both Engram and the Spring app bind to `127.0.0.1`. Do not expose this demo on a public network.
- For real apps with PII in prompts, enable [JamJet's redaction settings](https://docs.jamjet.dev/redaction) (Team tier and up).

## License

Apache 2.0. See [LICENSE](../../LICENSE).
