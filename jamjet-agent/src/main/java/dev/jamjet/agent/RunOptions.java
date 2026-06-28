package dev.jamjet.agent;

import java.time.Duration;

/**
 * Tuning knobs for a durable agent run ({@link Agent#runDurable(String, RunOptions)}),
 * mirroring the keyword arguments of the Python {@code Agent.run_durable}
 * ({@code max_turns}, {@code runtime_url}). All fields have sensible defaults via
 * {@link #defaults()}; override individually with the {@code with*} methods.
 *
 * @param maxTurns     static-unroll bound for the compiled agent loop (default
 *                     {@link Agent#DEFAULT_MAX_TURNS}); matches the Python default.
 * @param runtimeUrl   the JamJet engine base URL the run targets (default
 *                     {@value #DEFAULT_RUNTIME_URL}); a bare run never targets a
 *                     remote URL, mirroring the Python dev default.
 * @param bearerToken  optional {@code Authorization: Bearer} token, or {@code null}.
 * @param tenantId     optional {@code X-Tenant-Id}, or {@code null}.
 * @param pollInterval how often to poll {@code GET /executions/{id}} while waiting for
 *                     a terminal state (default {@value #DEFAULT_POLL_INTERVAL_MS} ms,
 *                     matching the Python {@code _POLL_INTERVAL_SECONDS}).
 * @param timeout      overall poll deadline, or {@code null} to derive it from the
 *                     agent's {@link Agent#timeoutSeconds()} (the Python behaviour:
 *                     {@code self.limits.timeout_seconds}).
 */
public record RunOptions(
        int maxTurns,
        String runtimeUrl,
        String bearerToken,
        String tenantId,
        Duration pollInterval,
        Duration timeout
) {
    /** The Python dev default ({@code http://127.0.0.1:7700}); a bare run never goes remote. */
    public static final String DEFAULT_RUNTIME_URL = "http://127.0.0.1:7700";

    /** The default poll interval in milliseconds (mirrors Python's 0.5s). */
    public static final long DEFAULT_POLL_INTERVAL_MS = 500;

    public RunOptions {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be >= 1");
        }
        if (runtimeUrl == null || runtimeUrl.isBlank()) {
            throw new IllegalArgumentException("runtimeUrl must not be blank");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        // timeout is nullable (null = derive from the agent's timeoutSeconds); when set it
        // must be a positive duration, never zero/negative.
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("timeout must be positive when set");
        }
    }

    /** The default options: {@code maxTurns=8}, local runtime, no auth, 500ms poll, agent-derived timeout. */
    public static RunOptions defaults() {
        return new RunOptions(
                Agent.DEFAULT_MAX_TURNS,
                DEFAULT_RUNTIME_URL,
                null,
                null,
                Duration.ofMillis(DEFAULT_POLL_INTERVAL_MS),
                null);
    }

    public RunOptions withMaxTurns(int maxTurns) {
        return new RunOptions(maxTurns, runtimeUrl, bearerToken, tenantId, pollInterval, timeout);
    }

    public RunOptions withRuntimeUrl(String runtimeUrl) {
        return new RunOptions(maxTurns, runtimeUrl, bearerToken, tenantId, pollInterval, timeout);
    }

    public RunOptions withAuth(String bearerToken, String tenantId) {
        return new RunOptions(maxTurns, runtimeUrl, bearerToken, tenantId, pollInterval, timeout);
    }

    public RunOptions withPollInterval(Duration pollInterval) {
        return new RunOptions(maxTurns, runtimeUrl, bearerToken, tenantId, pollInterval, timeout);
    }

    public RunOptions withTimeout(Duration timeout) {
        return new RunOptions(maxTurns, runtimeUrl, bearerToken, tenantId, pollInterval, timeout);
    }
}
