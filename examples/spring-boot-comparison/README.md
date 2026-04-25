# JamJet Spring Boot Comparison: Old Way vs New Way

Side-by-side comparison of building a durable Spring Boot agent the old way (REST sidecar) versus the new way (embedded runtime).

## At a Glance

| | Old Way (sidecar) | New Way (embedded) |
|---|---|---|
| **Prerequisites** | Java + Docker + Rust sidecar | Java only |
| **Config lines** | 5 (`runtime-url`, `durability.*`, `timeouts.*`) | 1 (`jamjet.storage: in-memory`) |
| **Processes** | 2 (Spring Boot + Rust sidecar) | 1 |
| **Startup overhead** | ~3-5s (Docker pull + sidecar boot) | ~1s (Spring Boot only) |
| **Crash recovery** | Sidecar holds state (single point of failure) | In-process (survives partial failures) |
| **Failure mode** | Sidecar down → silent durability loss | Runtime embedded → always available |
| **Code changes** | REST calls in each step | `DurabilityContext.replayOrExecute` in each step |
| **Dependency** | `jamjet-spring-boot-autoconfigure:0.1.2` (Maven Central) | `jamjet-spring-boot-starter:0.1.0-SNAPSHOT` (local) |

## How to Run: New Way (2 steps)

```bash
# 1. Install parent modules (first time only — from repo root)
cd ../..
mvn install -DskipTests

# 2. Start the application
cd examples/spring-boot-comparison/new-way
mvn spring-boot:run
```

That's it. The runtime is embedded. No Docker, no sidecar, no additional config.

## How to Run: Old Way (5 steps + Docker)

```bash
# 1. Pull and start the Rust sidecar
docker pull ghcr.io/jamjet-labs/jamjet-server:latest
docker run -d -p 7474:7474 ghcr.io/jamjet-labs/jamjet-server:latest

# 2. Wait for the sidecar to be healthy
curl -s http://localhost:7474/health

# 3. Start Postgres (the sidecar needs a state database)
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=jamjet postgres:16

# 4. Configure application.yml with the correct runtime-url and DB connection

# 5. Start the Spring Boot application
cd examples/spring-boot-comparison/old-way
mvn spring-boot:run
```

Note: old-way is a **reference project only**. It requires `dev.jamjet:jamjet-spring-boot-autoconfigure:0.1.2` from Maven Central. The sidecar REST endpoints (`/checkpoint/*`) shown in `OldWayAgent.java` are illustrative — they match the external runtime API pattern, not a public spec.

## What Changed

**New way** — one dependency, one line of config, one process:

```java
// NewWayAgent.java
@DurableAgent("research-pipeline")
@Service
public class NewWayAgent {

    @Checkpoint("search")
    public String search(String topic) {
        return DurabilityContext.current().replayOrExecute("search", () -> {
            return searchClient.query(topic);  // your real implementation
        });
    }
}
```

**Old way** — three dependencies, five config lines, two processes, REST calls in every method:

```java
// OldWayAgent.java
@Service
public class OldWayAgent {

    public String search(String topic) {
        // REST call to external sidecar — adds latency and a new failure point
        Map response = sidecar.post().uri("/checkpoint/search")
                .body(Map.of("topic", topic)).retrieve().body(Map.class);
        return response.get("result").toString();
    }
}
```

The runtime is now embedded in the same JVM. No network hop between your Spring Boot application and its durability layer.
