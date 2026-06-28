# jamjet-agent-spring-boot-starter

One-dependency Spring Boot authoring for the governed, durable JamJet `Agent`. Add the
starter, declare your `@Tool` beans and an `Agent` bean, and you get a background
`java_tool` worker wired to the engine, with governance (budget, policy, approval gates,
PII) compiled into the durable IR and enforced fail-closed by the Rust engine.

This starter wraps the framework-free `jamjet-agent` module only. It deliberately does not
put Spring AI or LangChain4j on the classpath: the agent runs on the Rust engine through
the model-seam sidecar, so its bean types always link.

## Add the dependency

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-agent-spring-boot-starter</artifactId>
    <version>0.3.1</version>
</dependency>
```

## What it auto-configures

| Bean | Purpose |
| --- | --- |
| `JamjetEngineClient` | Bare-route HTTP transport to the engine, built from `jamjet.agent.*`. |
| `ToolRegistry` | Populated from your `@Tool` Spring beans (CGLIB-aware). |
| `JavaToolWorker` (as a `SmartLifecycle`) | Drains the `java_tool` queue on a daemon thread for the life of the context. Gated by `jamjet.agent.worker.enabled` (default `true`). |

Every bean is `@ConditionalOnMissingBean`, so you can override any of them.

## Properties

| Property | Default | Notes |
| --- | --- | --- |
| `jamjet.agent.runtime-url` | `http://127.0.0.1:7700` | Engine base URL. |
| `jamjet.agent.bearer-token` | (none) | Sent as `Authorization: Bearer ...` when set. |
| `jamjet.agent.tenant-id` | (none) | Sent as `X-Tenant-Id` when set. |
| `jamjet.agent.worker.enabled` | `true` | Set `false` to author/compile without a background drain (e.g. tests). |
| `jamjet.agent.worker.id` | `jamjet-java-tool-worker` | Set a unique id per instance. |
| `jamjet.agent.worker.heartbeat-ms` | `10000` | Lease-renewal interval while a tool runs. |
| `jamjet.agent.worker.poll-backoff-ms` | `2000` | Backoff between empty claims. |

## Idiomatic authoring

Author tools as ordinary `@Component` beans:

```java
@Component
public class CalculatorTools {

    @Tool(name = "calculate", description = "Evaluate a binary arithmetic operation.")
    public String calculate(double a, double b, String op) {
        // ...
    }
}
```

Build the `Agent` over the auto-configured `ToolRegistry`. Use `.registry(registry)` (not
`.tools(...)`) so the agent's IR offers the model exactly the `@Tool` beans the background
worker can dispatch. Governance knobs compile into the IR:

```java
@Configuration(proxyBeanMethods = false)
public class CalculatorAgentConfig {

    @Bean
    public Agent calculatorAgent(ToolRegistry toolRegistry) {
        return Agent.builder("calculator_agent")
                .model("anthropic/claude-sonnet-4-6")
                .instructions("You are a precise calculator. Use the calculate tool for every step.")
                .registry(toolRegistry)                            // the @Tool-populated registry
                .budget(new Budget(50_000, 0.50))                  // cap tokens AND cost
                .policy(new PolicySetIr(
                        List.of("delete_*"),                       // blocked_tools
                        List.of(),                                 // require_approval_for
                        List.of("anthropic/claude-sonnet-4-6")))   // model_allowlist
                .approvalRequired(List.of("wire_*"))               // HITL gate
                .pii(true)
                .build();
    }
}
```

Drive it. Share the auto-configured `JamjetEngineClient` with the run so it stays on the
same connection the background worker drains:

```java
@Bean
CommandLineRunner runCalculator(Agent agent, JamjetEngineClient client) {
    return args -> {
        AgentResult result = agent.runDurable("What is (5 + 3) * 2?", client, RunOptions.defaults());
        System.out.println("answer: " + result.output());
    };
}
```

## Running services

A durable run is not self-contained. Two services must be running externally:

1. the JamJet engine (`jamjet-server`) at `jamjet.agent.runtime-url`, and
2. the model-seam sidecar (`JAMJET_MODEL_SEAM_URL`) that the engine routes governed model
   calls through.

The starter auto-provides only the third piece, the `java_tool` worker. This mirrors the
contract of the bare `Agent.runDurable` and the Python `agent.run_durable`.

## CGLIB-proxied tool beans

`@Tool` methods are discovered on the bean's user class. If a tool-holder is proxied (for
example `@Transactional`), the starter resolves the raw target so the tools are still
registered. Tools are then invoked directly on that target, bypassing the proxy's advice.
