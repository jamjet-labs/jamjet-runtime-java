# Spring AI + Engram + JamJet Cloud Demo

A multi-turn chat agent that **remembers facts across calls** via [Engram](https://github.com/jamjet-labs/jamjet/tree/main/runtime/engram-server) and is **observed end-to-end** by [JamJet Cloud](https://cloud.jamjet.dev) — drop in three Spring Boot starters, get durable memory + cloud observability for free.

## What this demo shows

- **Spring AI 1.0** chat agent using OpenAI for inference
- **`dev.jamjet:engram-spring-boot-starter`** autoconfigures `EngramClient` so the agent's `@Tool` methods can record + recall facts against a real Engram server
- **`dev.jamjet:jamjet-cloud-spring-boot-starter`** auto-instruments every chat call + tool span — no code changes
- **Cross-platform run flow** — works on macOS, Linux, and Windows with the same `mvnw` + `docker compose` commands

## How it's wired

```
User → POST /chat?session=alice ──→ Spring AI ChatClient
                                          │
                                          ├─→ OpenAI chat completion
                                          │
                                          └─→ @Tool methods (rememberFact / recallFacts)
                                                    │
                                                    └─→ EngramClient (autoconfigured)
                                                              │
                                                              └─→ Engram REST API (Docker)
```

JamJet Cloud's starter watches the whole flow via Spring AI's Micrometer Observation hooks and ships traces + cost rollups to the dashboard. **Zero observability code in your demo.**

## Prerequisites

- **Java 21+** (`--release 21`; Java 23 also works)
- **Docker Desktop** (or any Docker engine) — for the Engram sidecar
- **An OpenAI API key** — sign up at [platform.openai.com](https://platform.openai.com/api-keys)
- **A JamJet Cloud project** — sign up at [cloud.jamjet.dev](https://cloud.jamjet.dev), create a project, copy the API key

> **Cost note:** The OpenAI key is used twice per chat turn — once by Spring AI for the chat completion, and once by Engram for fact extraction (the LLM that turns "I prefer espresso" into a structured fact). Both calls use `gpt-4o-mini` by default.

## Run it

```bash
git clone https://github.com/jamjet-labs/jamjet-runtime-java
cd jamjet-runtime-java/examples/spring-ai-engram-cloud-demo

cp .env.example .env             # Windows: copy .env.example .env
# Edit .env — paste your OPENAI_API_KEY and JAMJET_API_KEY

docker compose up -d             # boots Engram on 127.0.0.1:9090
./mvnw spring-boot:run           # Windows: mvnw.cmd spring-boot:run
```

The app starts on `127.0.0.1:8080`. `PreflightCheck` validates both env vars and waits for Engram's `/health` endpoint before the server accepts requests — if either is missing or Engram is unreachable, the app exits with a clear error.

In another terminal:

```bash
# Tell the agent a fact — it stores it in Engram
curl -s -X POST "localhost:8080/chat?session=alice" \
  -H "Content-Type: text/plain" \
  -d "I work at Acme as a Java engineer"
# → {"session":"alice","reply":"Got it, I've stored that you work at Acme as a Java engineer."}

# Ask about it — agent recalls from Engram
curl -s -X POST "localhost:8080/chat?session=alice" \
  -H "Content-Type: text/plain" \
  -d "Where do I work?"
# → {"session":"alice","reply":"You work at Acme."}

# The first message also contained "Java engineer" — Engram extracted that too
curl -s -X POST "localhost:8080/chat?session=alice" \
  -H "Content-Type: text/plain" \
  -d "What languages do I use?"
# → {"session":"alice","reply":"Based on what you've shared, you're a Java engineer."}
```

Each response is a JSON object `{"session": "...", "reply": "..."}`.

## See the trace in JamJet Cloud

Open [cloud.jamjet.dev/dashboard/graph](https://cloud.jamjet.dev/dashboard/graph) — each `/chat` call appears as a trace with:

- 1 LLM span (OpenAI chat completion)
- 1 or more Engram tool spans (`rememberFact` or `recallFacts`)
- Cost rollup (per-token, per-call)

## Anatomy

The interesting code is ~120 LOC across 4 files:

| File | What it does |
|---|---|
| `MemoryTools.java` | `@Tool` methods (`rememberFact`, `recallFacts`) backed by autoconfigured `EngramClient` |
| `MemoryAgent.java` | Spring AI `ChatClient` wired with the tools + system prompt |
| `ChatController.java` | `POST /chat?session=X` — accepts `text/plain`, returns `{"session","reply"}` |
| `startup/PreflightCheck.java` | Validates env vars + polls Engram `/health` before the app accepts traffic |

The pom has three starter dependencies. Zero custom plumbing.

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `engram.base-url` | `http://127.0.0.1:9090` | Where the autoconfigured `EngramClient` connects |
| `spring.ai.openai.api-key` | `${OPENAI_API_KEY}` | Spring AI OpenAI key |
| `spring.ai.openai.chat.options.model` | `gpt-4o-mini` | OpenAI model for chat |
| `jamjet.cloud.api-key` | `${JAMJET_API_KEY}` | JamJet Cloud project key |
| `jamjet.cloud.api-url` | `https://api.jamjet.dev` | JamJet Cloud ingest endpoint |

To swap the chat model (e.g. to `gpt-4o`), edit `application.yml`. To use a different LLM provider for Engram's fact extraction, change `ENGRAM_LLM_PROVIDER` in `docker-compose.yml` — see [Engram's provider docs](https://github.com/jamjet-labs/jamjet/tree/main/runtime/engram-server#llm-providers).

## Windows notes

- Use PowerShell or cmd; `mvnw.cmd` is the entry point instead of `./mvnw`.
- WSL2 users: run from the WSL side for cleanest networking with Docker Desktop.
- The `ghcr.io/jamjet-labs/engram-server:0.5.0` image is multi-arch; Docker Desktop on Windows ARM should work with Linux containers enabled.

## Cleaning up

```bash
docker compose down              # stops Engram, removes container + volume
# Press Ctrl-C on the Spring app
```

When you're done, rotate or delete your JamJet API key and OpenAI key in their respective dashboards — they are tied to your account quotas.

## Security

- `.env` is in `.gitignore` — never commit your keys.
- Both Engram and the Spring app bind to `127.0.0.1`. Do not expose this demo on a public network.
- For real apps with PII in prompts, enable [JamJet's redaction settings](https://docs.jamjet.dev/redaction) (Team tier and up). The demo runs on the free tier without redaction — do not pipe production traffic through it.

## What's next

- **The new Python Engram rewrite** (`jamjet-engram` 0.1.0) is the next-generation server — more featureful, better benchmarks, but currently has wire-protocol gaps with the Java starter. Once those gaps close, the docker-compose image is a one-line swap.
- **A separate MCP demo** (`examples/mcp-engram-demo/`, coming soon) targets MCP-protocol-native clients (Cursor, Claude Desktop) instead of Spring Boot ergonomics.
- Read the [JamJet Spring AI integration guide](../../docs/spring-ai-integration.md).

## License

Apache 2.0. See [LICENSE](../../LICENSE).
