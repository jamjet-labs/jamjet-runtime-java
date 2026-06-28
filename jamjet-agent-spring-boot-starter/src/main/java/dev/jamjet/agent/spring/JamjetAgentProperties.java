package dev.jamjet.agent.spring;

import dev.jamjet.agent.RunOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the JamJet agent Spring Boot starter — the knobs the
 * auto-configured {@link dev.jamjet.agent.client.JamjetEngineClient} and the durable
 * {@link dev.jamjet.agent.worker.JavaToolWorker} read. All properties are prefixed
 * with {@code jamjet.agent}.
 *
 * <p>The endpoint defaults mirror the framework-free {@link RunOptions} ({@code runtime-url}
 * defaults to {@link RunOptions#DEFAULT_RUNTIME_URL}), so a Spring app and a bare
 * {@code Agent.runDurable} target the same local engine out of the box.
 */
@ConfigurationProperties(prefix = "jamjet.agent")
public class JamjetAgentProperties {

    /**
     * The JamJet engine base URL the client and worker target, e.g.
     * {@code http://127.0.0.1:7700}. Defaults to {@link RunOptions#DEFAULT_RUNTIME_URL}.
     */
    private String runtimeUrl = RunOptions.DEFAULT_RUNTIME_URL;

    /** Optional bearer token, sent as {@code Authorization: Bearer ...} when set. */
    private String bearerToken;

    /** Optional tenant id, sent as {@code X-Tenant-Id} when set. */
    private String tenantId;

    /** The durable {@code java_tool} worker settings. */
    private Worker worker = new Worker();

    public String getRuntimeUrl() {
        return runtimeUrl;
    }

    public void setRuntimeUrl(String runtimeUrl) {
        this.runtimeUrl = runtimeUrl;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Worker getWorker() {
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker;
    }

    /**
     * Settings for the background {@link dev.jamjet.agent.worker.JavaToolWorker} that
     * drains the {@code java_tool} queue. Defaults mirror the worker's own constants
     * ({@code heartbeat-ms} = 10s, {@code poll-backoff-ms} = 2s).
     */
    public static class Worker {

        /**
         * Whether the auto-configuration starts a background {@code java_tool} worker with
         * the application context. Set {@code false} to author/compile agents (or run a
         * worker out-of-process) without a background drain — e.g. in tests.
         */
        private boolean enabled = true;

        /** This worker's stable id (echoed on claim + heartbeat). Set a unique id per instance. */
        private String id = "jamjet-java-tool-worker";

        /** Lease-renewal heartbeat interval in milliseconds while a tool runs. */
        private long heartbeatMs = 10_000;

        /** Backoff in milliseconds between empty claims in the worker poll loop. */
        private long pollBackoffMs = 2_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public long getHeartbeatMs() {
            return heartbeatMs;
        }

        public void setHeartbeatMs(long heartbeatMs) {
            this.heartbeatMs = heartbeatMs;
        }

        public long getPollBackoffMs() {
            return pollBackoffMs;
        }

        public void setPollBackoffMs(long pollBackoffMs) {
            this.pollBackoffMs = pollBackoffMs;
        }
    }
}
