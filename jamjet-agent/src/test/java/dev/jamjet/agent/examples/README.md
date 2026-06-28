# Example: a governed, durable calculator agent (Java)

A worked end-to-end example of authoring a JamJet agent in idiomatic Java with
`dev.jamjet.agent.Agent`, running it durably and governed on the Rust engine. Every
call here is the real shipped `jamjet-agent` API.

- `CalculatorTools` — a plain class with one `@Tool` method (`calculate`).
- `CalculatorAgentExample` — `buildAgent()` is the whole authoring surface; `main`
  runs it durably against a local engine.
- `CalculatorAgentExampleTest` — drives the same agent end-to-end against a WireMock
  stub of the engine (a live engine + sidecar is not available in CI).

## Authoring

```java
Agent agent = Agent.builder("calculator_agent")
        .model("anthropic/claude-sonnet-4-6")
        .instructions("You are a precise calculator. Use the calculate tool for every arithmetic step.")
        .tools(new CalculatorTools())
        // governance: compiled into the IR, enforced fail-closed by the engine
        .budget(new Budget(50_000, 0.50))                       // token + cost cap
        .policy(new PolicySetIr(
                List.of("delete_*"),                            // blocked_tools
                List.of(),                                      // require_approval_for
                List.of("anthropic/claude-sonnet-4-6")))        // model_allowlist
        .approvalRequired(List.of("wire_*"))                    // HITL gate on wire_* tools
        .pii(true)                                              // PII redaction metadata
        .build();
```

`agent.compileToIr()` produces the same agent-loop `WorkflowIr` the Python ADK emits
(the Java tool nodes are `java_fn` where Python's are `python_fn`), so a Java agent and
a Python agent run on the identical engine. The governance knobs ride in the IR; the
engine enforces them, not the Java SDK.

## Running it durably

```java
RunOptions options = RunOptions.defaults();  // local engine at http://127.0.0.1:7700

try (JamjetEngineClient client = new JamjetEngineClient(options.runtimeUrl());
     JavaToolWorker worker = new JavaToolWorker(client, "calculator-worker-1", agent.registry())) {

    Thread workerThread = new Thread(worker::run, "calculator-java-tool-worker");
    workerThread.setDaemon(true);
    workerThread.start();

    AgentResult result = agent.runDurable("What is (5 + 3) * 2?", client, options);
    System.out.println(result.output());      // the final assistant text
    System.out.println(result.toolCalls());   // the calculate call + its result

    worker.stop();
}
```

`runDurable` compiles the IR, registers it (`POST /workflows`), starts an execution
seeded with the system+user messages (`POST /executions`), polls
`GET /executions/{id}` to a terminal state, and extracts the answer from
`current_state.last_model_output` (falling back to the last assistant message). A
non-`completed` terminal (`failed` / `cancelled` / `limit_exceeded`, e.g. a budget
breach) raises `AgentRunException`; a run that never terminates raises
`AgentRunTimeoutException`.

## Required running services

A durable run is not self-contained. Three services must be live (the same contract as
the Python `agent.run_durable`):

1. **the JamJet engine** (`jamjet-server`) at `RunOptions.runtimeUrl()`, which owns the
   `java_tool` queue;
2. **the model sidecar** (`JAMJET_MODEL_SEAM_URL`), through which the engine routes
   every governed model call (there is no Java model code);
3. **a `JavaToolWorker`** draining the `java_tool` queue with this agent's tool
   registry, so the `@Tool` methods execute durably exactly-once. Run it in a separate
   thread or process; `runDurable` does not start one.
