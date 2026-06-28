package dev.jamjet.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.ir.WorkflowIr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Plain-Java HTTP client for the JamJet engine REST API — the load-bearing
 * Java&rarr;engine transport for the Track-9 Java ADK.
 *
 * <p>Built on the JDK {@link java.net.http.HttpClient} over a virtual-thread
 * executor (no third-party HTTP dependency), serializing every body through
 * {@link JamjetJson#shared()} (snake_case, {@code NON_NULL}) so Java JSON is
 * wire-compatible with the Rust engine. It mirrors the canonical Python
 * {@code jamjet.client.JamjetClient}: the engine serves <strong>bare</strong>
 * routes ({@code /workflows}, {@code /executions}, {@code /work-items/...}),
 * <em>not</em> {@code /api/v1/...}.
 *
 * <p>Unlike jamjet-spring's {@code JamjetRuntimeClient} (which posts to the wrong
 * {@code /api/v1} routes and lacks the worker protocol), this client uses bare
 * routes and adds the four work-item methods the durable Java tool-worker needs:
 * {@link #claimWorkItem}, {@link #completeWorkItem}, {@link #failWorkItem},
 * {@link #heartbeatWorkItem}.
 *
 * <p><strong>Lease fencing.</strong> The claim response carries a {@code lease_fence}
 * (engine PR #108). A worker must thread it back on both {@code heartbeat} and
 * {@code complete}; a stale fence on complete is rejected with HTTP 409, which this
 * client surfaces as a {@link JamjetHttpException} where {@link JamjetHttpException#isConflict()}
 * is true, so the worker can treat it as a lost lease rather than a generic error.
 *
 * <p>Request bodies are hand-built with literal snake_case keys (faithful to the
 * Python client). This is deliberate: {@code JamjetJson}'s SNAKE_CASE strategy
 * renames record/bean property names but NOT {@code Map} keys, so building bodies
 * as maps with camelCase keys would emit the wrong wire shape.
 */
public final class JamjetEngineClient implements AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String bearerToken;
    private final String tenantId;
    private final HttpClient http;
    private final ObjectMapper json;

    /** Anonymous client (no auth headers) against {@code baseUrl}. */
    public JamjetEngineClient(String baseUrl) {
        this(baseUrl, null, null);
    }

    /**
     * @param baseUrl     engine base URL, e.g. {@code http://localhost:7700}
     * @param bearerToken optional; sent as {@code Authorization: Bearer ...} when non-blank
     * @param tenantId    optional; sent as {@code X-Tenant-Id} when non-blank
     */
    public JamjetEngineClient(String baseUrl, String bearerToken, String tenantId) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.bearerToken = bearerToken;
        this.tenantId = tenantId;
        this.http = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.json = JamjetJson.shared();
    }

    // -- Workflows --------------------------------------------------------------

    /** {@code POST /workflows} with a typed IR. Body {@code {"ir": ir}}. */
    public CreateWorkflowResult createWorkflow(WorkflowIr ir) {
        return postJson("/workflows", Map.of("ir", ir), CreateWorkflowResult.class);
    }

    /** {@code POST /workflows} with a raw IR map. Body {@code {"ir": ir}}. */
    public CreateWorkflowResult createWorkflow(Map<String, Object> ir) {
        return postJson("/workflows", Map.of("ir", ir), CreateWorkflowResult.class);
    }

    // -- Executions -------------------------------------------------------------

    /**
     * {@code POST /executions}. Body {@code {workflow_id, input, workflow_version?}}
     * (note: the version key is {@code workflow_version}, matching the Python client).
     */
    public StartExecutionResult startExecution(String workflowId,
                                               Map<String, Object> initialInput,
                                               String workflowVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflow_id", workflowId);
        body.put("input", initialInput == null ? Map.of() : initialInput);
        if (workflowVersion != null && !workflowVersion.isBlank()) {
            body.put("workflow_version", workflowVersion);
        }
        return postJson("/executions", body, StartExecutionResult.class);
    }

    /** {@code GET /executions/{id}} — the current execution snapshot for polling. */
    public ExecutionState getExecution(String executionId) {
        return getJson("/executions/" + executionId, ExecutionState.class);
    }

    /** {@code GET /executions/{id}/events} — the event stream (response {@code {"events": [...]}}). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listEvents(String executionId) {
        String body = execute(buildGet("/executions/" + executionId + "/events"));
        Map<String, Object> resp = (Map<String, Object>) parse(body, Map.class);
        if (resp == null) {
            return List.of();
        }
        Object events = resp.get("events");
        if (events instanceof List<?> list) {
            return list.stream().map(e -> (Map<String, Object>) e).toList();
        }
        return List.of();
    }

    // -- Work items (worker protocol) ------------------------------------------

    /**
     * {@code POST /work-items/claim}. Body {@code {worker_id, queue_types}}.
     * Returns the claimed item, or {@link Optional#empty()} when the queue is empty
     * (engine responds {@code {"claimed": false}}).
     */
    public Optional<ClaimedWorkItem> claimWorkItem(String workerId, List<String> queueTypes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("worker_id", workerId);
        body.put("queue_types", queueTypes);
        JsonNode root = readTree(execute(buildPost("/work-items/claim", body)));
        if (root == null || !root.path("claimed").asBoolean(false)) {
            return Optional.empty();
        }
        JsonNode item = root.get("work_item");
        if (item == null || item.isNull()) {
            return Optional.empty();
        }
        return Optional.of(json.convertValue(item, ClaimedWorkItem.class));
    }

    /**
     * {@code POST /work-items/{id}/complete} — mark the item done.
     * Body {@code {output, state_patch, duration_ms, execution_id?, node_id?,
     * gen_ai_model?, finish_reason?, lease_fence?}}.
     *
     * <p>{@code leaseFence} is included in the body only when non-null; when present
     * the engine fences the completion, so a stale fence (the item was reclaimed)
     * yields HTTP 409, surfaced as a {@link JamjetHttpException} with
     * {@link JamjetHttpException#isConflict()} true.
     */
    public void completeWorkItem(String itemId,
                                 String executionId,
                                 String nodeId,
                                 Object output,
                                 Map<String, Object> statePatch,
                                 long durationMs,
                                 String genAiModel,
                                 String finishReason,
                                 Long leaseFence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("output", output);
        body.put("state_patch", statePatch == null ? Map.of() : statePatch);
        body.put("duration_ms", durationMs);
        if (executionId != null) {
            body.put("execution_id", executionId);
        }
        if (nodeId != null) {
            body.put("node_id", nodeId);
        }
        if (genAiModel != null) {
            body.put("gen_ai_model", genAiModel);
        }
        if (finishReason != null) {
            body.put("finish_reason", finishReason);
        }
        if (leaseFence != null) {
            body.put("lease_fence", leaseFence);
        }
        execute(buildPost("/work-items/" + itemId + "/complete", body));
    }

    /** {@code POST /work-items/{id}/fail}. Body {@code {error}}. */
    public void failWorkItem(String itemId, String error) {
        execute(buildPost("/work-items/" + itemId + "/fail", Map.of("error", error)));
    }

    /**
     * {@code POST /work-items/{id}/heartbeat} — renew the lease.
     * Body {@code {worker_id, lease_fence?}}; the fence must match the claim's value
     * or the engine rejects the renewal.
     *
     * <p>{@code leaseFence} is nullable — consistent with {@link #completeWorkItem} and
     * {@link ClaimedWorkItem#leaseFence()} — and included in the body only when non-null.
     * For a real fenced claim it is always present; null is only the legacy unfenced case.
     */
    public void heartbeatWorkItem(String itemId, String workerId, Long leaseFence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("worker_id", workerId);
        if (leaseFence != null) {
            body.put("lease_fence", leaseFence);
        }
        execute(buildPost("/work-items/" + itemId + "/heartbeat", body));
    }

    // -- Internal HTTP plumbing -------------------------------------------------

    private <T> T postJson(String path, Object body, Class<T> type) {
        return parse(execute(buildPost(path, body)), type);
    }

    private <T> T getJson(String path, Class<T> type) {
        return parse(execute(buildGet(path)), type);
    }

    private HttpRequest buildPost(String path, Object body) {
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new JamjetHttpException("Failed to serialize request body for " + path, e);
        }
        return withHeaders(HttpRequest.newBuilder(uri(path)))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
    }

    private HttpRequest buildGet(String path) {
        return withHeaders(HttpRequest.newBuilder(uri(path)))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
    }

    private HttpRequest.Builder withHeaders(HttpRequest.Builder builder) {
        builder.header("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            builder.header("X-Tenant-Id", tenantId);
        }
        return builder;
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    /** Send, throwing {@link JamjetHttpException} on a non-2xx (status + body preserved). */
    private String execute(HttpRequest request) {
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new JamjetHttpException("Request timed out: " + request.uri(), e);
        } catch (IOException e) {
            throw new JamjetHttpException("HTTP request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JamjetHttpException("HTTP request interrupted: " + request.uri(), e);
        }
        int status = resp.statusCode();
        String body = resp.body();
        if (status < 200 || status >= 300) {
            throw new JamjetHttpException(status, body);
        }
        return body;
    }

    private <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readValue(body, type);
        } catch (IOException e) {
            throw new JamjetHttpException("Failed to parse response body: " + e.getMessage(), e);
        }
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new JamjetHttpException("Failed to parse response body: " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            throw new IllegalArgumentException("baseUrl must not be null");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** HttpClient is JVM-managed and the virtual-thread executor has no pool to drain. */
    @Override
    public void close() {
        // no-op
    }
}
