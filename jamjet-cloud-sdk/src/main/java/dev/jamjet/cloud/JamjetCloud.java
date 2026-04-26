package dev.jamjet.cloud;

import dev.jamjet.cloud.http.CloudHttpClient;

import java.util.Objects;

/**
 * JamJet Cloud SDK entry point.
 *
 * <p>Mirrors the Python {@code jamjet.cloud} module. Two-line drop-in:
 * <pre>{@code
 *   JamjetCloud.configure(JamjetCloudConfig.builder()
 *       .apiKey(System.getenv("JJ_API_KEY"))
 *       .project("my-app")
 *       .build());
 *   // ...your existing Spring AI / LangChain4j code...
 * }</pre>
 *
 * <p>The Spring Boot starter ({@code jamjet-cloud-spring-boot-starter}) wraps
 * this in auto-configuration so {@code application.yaml} is enough.
 *
 * <p><b>Plan 5 Phase 1.7-1.10 scope (initial release).</b> This module ships
 * the foundation: configuration, agent identity, span emission, HTTP ingest.
 * Auto-instrumentation of Spring AI {@code ChatClient} and LangChain4j
 * {@code ChatLanguageModel}, plus W3C trace propagation, ride in subsequent
 * commits — the SDK's surface is stable from day one but not yet wired into
 * every framework.
 */
public final class JamjetCloud {

    private static volatile JamjetCloudConfig config;
    private static volatile EventQueue queue;
    private static volatile CloudHttpClient httpClient;

    private JamjetCloud() {
        // utility
    }

    /**
     * Initialize the SDK. Call once at process start.
     *
     * <p>Thread-safe. Subsequent calls replace the previous config; in-flight
     * spans flush against the old config before the swap.
     */
    public static synchronized void configure(JamjetCloudConfig newConfig) {
        Objects.requireNonNull(newConfig, "config");
        Objects.requireNonNull(newConfig.apiKey(), "config.apiKey");

        // Drain any in-flight queue against the old client before swapping.
        if (queue != null) {
            queue.stop();
        }

        config = newConfig;
        httpClient = new CloudHttpClient(newConfig);
        queue = new EventQueue(httpClient, newConfig.flushIntervalMs(), newConfig.flushSize());
        queue.start();

        // Seed the implicit default agent — every span is attributable.
        AgentContext.setDefault(new Agent(
                newConfig.agentName() != null ? newConfig.agentName() : "default",
                null,
                null
        ));
    }

    /**
     * Create an agent identity. The handle implements {@link AutoCloseable}
     * for try-with-resources scoping; spans created inside the resource are
     * tagged with this agent.
     *
     * <pre>{@code
     *   try (Agent.Scope scope = JamjetCloud.agent("research-bot").enter()) {
     *       // ChatClient calls here are tagged with research-bot
     *   }
     * }</pre>
     */
    public static Agent agent(String name) {
        return agent(name, null, null);
    }

    public static Agent agent(String name, String cardUri, String description) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("agent name cannot be blank");
        }
        return new Agent(name.trim(), cardUri, description);
    }

    /**
     * Open a new span belonging to the current trace, tagged with the active
     * agent. Spans must be {@link Span#finish(String) finished} (or failed)
     * to be emitted.
     */
    public static Span newSpan(String kind, String name) {
        ensureConfigured();
        return TraceContext.current().newSpan(kind, name);
    }

    /** Emit a finished span to the cloud (called by Span.finish/fail). */
    static void emit(Span span) {
        EventQueue q = queue;
        if (q != null) {
            q.push(span.toEventMap());
        }
    }

    /** Internal accessor for HTTP client (used by future auto-instrumentation). */
    static CloudHttpClient httpClient() {
        return httpClient;
    }

    static JamjetCloudConfig config() {
        return config;
    }

    private static void ensureConfigured() {
        if (config == null) {
            throw new IllegalStateException(
                    "JamjetCloud not configured. Call JamjetCloud.configure(...) first."
            );
        }
    }
}
