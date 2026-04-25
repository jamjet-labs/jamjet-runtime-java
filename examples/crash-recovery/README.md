# JamJet Crash Recovery Demo

Demonstrates how `DurabilityContext` checkpoints survive process restarts, enabling an
agent workflow to resume exactly where it left off without re-running completed steps.

## What This Demo Shows

A three-step research agent (`search → analyze → synthesize`) is run in two separate
process invocations:

**Phase 1 (CrashSimulator):** executes `search` and `analyze`, checkpoints both to disk,
then calls `System.exit(1)` to simulate a process crash before `synthesize` runs.

**Phase 2 (RecoveryRunner):** loads the checkpoint file into a fresh `DurabilityContext`,
enables replay mode, and re-runs all three steps. `search` and `analyze` return instantly
from the checkpoint cache; `synthesize` executes fresh.

```
Phase 1                                 Phase 2 (after restart)
──────────────────────────────────     ──────────────────────────────────────
[1/3] search     500ms  ✓ checkpoint   [1/3] search     0ms   ⚡ skipped
[2/3] analyze    500ms  ✓ checkpoint   [2/3] analyze    0ms   ⚡ skipped
[CRASH] exit(1)                        [3/3] synthesize 500ms ✓ completed
                                       Total: ~500ms  (saved ~1000ms)
```

## Architecture

```
                          ┌─────────────────────────┐
  Phase 1                 │  DurabilityContext       │
  ─────────────────────   │  (thread-local)          │
  search()   → execute    │   checkpointIds: [       │
  analyze()  → execute    │     "search",            │  serialized to
             → checkpoint │     "analyze"            │  recovery-demo-state.dat
  exit(1)                 │   ]                      │──────────────────────►
                          └─────────────────────────┘

                          ┌─────────────────────────┐
  Phase 2                 │  DurabilityContext       │
  ─────────────────────   │  (fresh, replay=true)   │
  load state              │   checkpointIds: [       │◄──────────────────────
  search()   → SKIP       │     "search",            │  loaded from disk
  analyze()  → SKIP       │     "analyze"            │
  synthesize() → execute  │   ]                      │
                          └─────────────────────────┘
```

## Why the Old SDK Can't Do This

Traditional agent frameworks (LangChain, LlamaIndex, Spring AI) do not provide
durable execution semantics. If a process crashes mid-workflow:

- **No checkpoint:** the entire workflow must restart from step 1.
- **Duplicate side effects:** LLM calls, web searches, and tool invocations re-run.
- **Cost:** wasted tokens and API calls.
- **Inconsistency:** tools with side effects (writes, emails, payments) may execute twice.

`DurabilityContext` solves this by recording each step result under a stable checkpoint ID.
On restart, any previously-seen ID is returned from the cache without calling the supplier —
making replay both instantaneous and exactly-once.

## How to Run

**Prerequisites:** the parent project must be installed locally first:

```bash
# From the repo root:
mvn install -DskipTests
```

**Phase 1 — crash after step 2:**

```bash
cd examples/crash-recovery
mvn compile exec:java
# Exit code 1 is expected (simulated crash)
```

**Phase 2 — resume from checkpoint:**

```bash
mvn exec:java -Dexec.mainClass=dev.jamjet.examples.recovery.RecoveryRunner
```

## Expected Output

**Phase 1:**

```
╔══════════════════════════════════════════════════════════╗
║  Phase 1: Normal execution (will crash after step 2)    ║
╚══════════════════════════════════════════════════════════╝

[1/3] Executing search for "quantum computing"...            checkpointed (501ms)
[2/3] Executing analyze...                                   checkpointed (502ms)

[CRASH] Process killed after checkpoint 2.
```

**Phase 2:**

```
╔══════════════════════════════════════════════════════════╗
║  Phase 2: Recovery — resuming from last checkpoint       ║
╚══════════════════════════════════════════════════════════╝

[1/3] Replaying search from checkpoint...                    skipped (0ms)
[2/3] Replaying analyze from checkpoint...                   skipped (0ms)
[3/3] Executing synthesize...                                completed (501ms)

Recovery complete.
  Replayed:     2 checkpoints (saved ~1000ms)
  Re-executed:  1 step
  Total time:   502ms (vs ~1500ms without recovery)
```

## Project Layout

```
crash-recovery/
├── pom.xml
└── src/main/java/dev/jamjet/examples/recovery/
    ├── ResearchAgent.java     — @DurableAgent with 3 checkpointed methods
    ├── CrashSimulator.java    — Phase 1: execute steps 1-2, save state, exit(1)
    └── RecoveryRunner.java    — Phase 2: load state, replay steps 1-2, execute step 3
```

The state file (`recovery-demo-state.dat`) is written to the current directory and contains
a serialized `LinkedHashMap<String, String>` mapping checkpoint IDs to their recorded values.
