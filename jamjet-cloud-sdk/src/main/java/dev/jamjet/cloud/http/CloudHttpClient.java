package dev.jamjet.cloud.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.cloud.JamjetCloudConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client to api.jamjet.dev. Built on java.net.http (no extra deps).
 * Synchronous send for simplicity in v1; async/streaming arrive when there's
 * concrete demand.
 */
public final class CloudHttpClient {

    private final JamjetCloudConfig config;
    private final HttpClient http;
    private final ObjectMapper json;

    public CloudHttpClient(JamjetCloudConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.json = new ObjectMapper();
    }

    /** POST /v1/events/ingest. Throws on non-2xx. */
    public void postEvents(List<Map<String, Object>> events) throws Exception {
        Map<String, Object> body = Map.of("events", events);
        byte[] payload = json.writeValueAsBytes(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(config.apiUrl() + "/v1/events/ingest"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException(
                    "events ingest " + resp.statusCode() + ": " + resp.body()
            );
        }
    }

    /**
     * POST /v1/policy/evaluate. Returns the decision. Fail-open: callers
     * should treat null/exception as "allow" so cloud outages don't block agents.
     */
    public Map<String, Object> evaluatePolicy(String agent, String tool,
                                              Double costUsd, Map<String, Object> context) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("agent", agent);
        body.put("tool", tool);
        if (costUsd != null) body.put("cost_usd", costUsd);
        if (context != null) body.put("context", context);
        byte[] payload = json.writeValueAsBytes(body);

        HttpRequest req = HttpRequest.newBuilder(URI.create(config.apiUrl() + "/v1/policy/evaluate"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException(
                    "policy/evaluate " + resp.statusCode() + ": " + resp.body()
            );
        }
        return json.readValue(resp.body(), java.util.Map.class);
    }
}
