# JamJet Latency Comparison Benchmark

Measures the latency difference between two runtime deployment topologies for an
identical 3-node sequential workflow (`fetch → transform → store`).

## What's Measured

```
┌─────────────────────────────────────────────────────────────────────┐
│  Embedded path (new)                                                │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Your Process                                                │  │
│  │   ┌──────────┐   in-memory   ┌───────────┐   direct call    │  │
│  │   │ Scheduler├──────────────>│ WorkQueue ├──────────────>   │  │
│  │   └──────────┘               └───────────┘    Worker        │  │
│  └──────────────────────────────────────────────────────────────┘  │
│  Zero network hops. All state in ConcurrentHashMap.                │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  REST sidecar path (old)                                            │
│                                                                     │
│  ┌──────────────┐  HTTP   ┌───────────────┐  HTTP  ┌───────────┐  │
│  │ Your Process ├────────>│ Runtime Server├───────>│  Worker   │  │
│  └──────────────┘         └───────────────┘        └───────────┘  │
│                                                                     │
│  Each operation (enqueue, claim, complete) = 1+ round-trip.        │
│  3-node workflow = 11 HTTP round-trips.                            │
└─────────────────────────────────────────────────────────────────────┘
```

The REST sidecar is simulated with [WireMock](https://wiremock.org/) — responses are
immediate stubs (no real server logic), so measured latency reflects pure HTTP overhead,
not server processing time. In a real deployment the REST sidecar would be slower still.

## How to Run

```bash
# From the examples/latency-comparison directory:
mvn compile exec:java
```

Prerequisites: the parent project must be installed into your local Maven repository:

```bash
# From the repo root:
mvn install -DskipTests
```

## Expected Output

```
╔══════════════════════════════════════════════════════════╗
║     JamJet Runtime Benchmark — 3-node workflow          ║
╚══════════════════════════════════════════════════════════╝

Phase 1: Single Workflow Latency (1000 iterations)
                             p50      p95      p99
Embedded runtime           0.05ms   0.12ms   0.30ms
REST sidecar               1.80ms   3.50ms   6.00ms
Overhead saved               97%      96%      95%

Phase 2: Concurrent Throughput (100 simultaneous workflows)
Embedded:  100/100 completed in 45ms   (avg 0.45ms/workflow)
REST:      100/100 completed in 320ms  (avg 3.20ms/workflow)
Speedup:   7.1x
```

Actual numbers will vary by machine, JVM warm-up, and OS scheduling.

## How to Interpret

| Metric | What it means |
|--------|---------------|
| **p50** | Median latency — typical workflow cost |
| **p95** | 95th percentile — most workflows finish under this |
| **p99** | 99th percentile — worst-case tail latency |
| **Overhead saved** | `(rest_p50 − embedded_p50) / rest_p50 × 100%` |
| **Speedup** | `rest_total_ms / embedded_total_ms` |

The benchmark deliberately uses stub executors (0ms work) so that all measured latency
is pure framework overhead: state transitions, event emission, and queue management.
Real agent workloads add their own latency on top; the embedded path saves the same
absolute amount regardless of how long the actual work takes.

## Project Layout

```
latency-comparison/
├── pom.xml
└── src/main/java/dev/jamjet/examples/benchmark/
    ├── BenchmarkRunner.java       — main entry point
    ├── NewPathBenchmark.java      — embedded runtime benchmark
    ├── OldPathBenchmark.java      — REST sidecar benchmark (WireMock)
    ├── ConcurrencyBenchmark.java  — 100-concurrent-workflow test
    ├── StubNodeExecutor.java      — zero-delay node executor
    └── BenchmarkReport.java       — percentile calculation + formatted output
```
