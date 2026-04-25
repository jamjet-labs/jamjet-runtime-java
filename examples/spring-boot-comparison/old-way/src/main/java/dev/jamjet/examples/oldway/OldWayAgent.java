package dev.jamjet.examples.oldway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Old-way agent: every durable step is a REST call to the Rust sidecar.
 *
 * <p>This simulates the JamjetDurabilityAdvisor pattern from the 0.1.x era:
 * the Spring service itself holds no state — it relies on the external runtime to
 * checkpoint results and replay them on resume. If the sidecar is down, all durability
 * guarantees are lost.</p>
 *
 * <p>Compare with {@code new-way/NewWayAgent}: same three steps, no HTTP calls,
 * no sidecar dependency, no Docker.</p>
 */
@Service
public class OldWayAgent {

    private final RestClient sidecar;

    public OldWayAgent(@Value("${jamjet.runtime-url:http://localhost:7474}") String runtimeUrl) {
        this.sidecar = RestClient.builder()
                .baseUrl(runtimeUrl)
                .build();
    }

    /**
     * Searches for sources — checkpointed via REST call to the sidecar.
     *
     * <p>Old way: POST /checkpoint/search to the external runtime.
     * If the sidecar is reachable, it either replays the cached result or executes the step.
     * If the sidecar is down, the step runs locally without durability.</p>
     */
    public String search(String topic) {
        // Old way: delegate checkpoint to external sidecar over REST
        try {
            Map<?, ?> response = sidecar.post()
                    .uri("/checkpoint/search")
                    .body(Map.of("topic", topic))
                    .retrieve()
                    .body(Map.class);
            return response != null ? response.getOrDefault("result", "").toString() : "";
        } catch (Exception e) {
            // Sidecar unavailable: run locally, losing durability guarantee
            return "Found 5 papers on: " + topic;
        }
    }

    /**
     * Analyzes sources — checkpointed via REST call to the sidecar.
     *
     * <p>Two network round-trips happen here: one to the sidecar and one (inside the
     * sidecar) to the LLM. Latency and failure surface area are both doubled.</p>
     */
    public String analyze(String sources) {
        try {
            Map<?, ?> response = sidecar.post()
                    .uri("/checkpoint/analyze")
                    .body(Map.of("sources", sources))
                    .retrieve()
                    .body(Map.class);
            return response != null ? response.getOrDefault("result", "").toString() : "";
        } catch (Exception e) {
            return "Analysis complete: " + sources.substring(0, Math.min(sources.length(), 30));
        }
    }

    /**
     * Synthesizes the final report — checkpointed via REST call to the sidecar.
     *
     * <p>Process crash between steps requires the sidecar to have persisted the previous
     * checkpoints. If the sidecar itself crashed, all in-flight checkpoints are lost.</p>
     */
    public String synthesize(String analysis) {
        try {
            Map<?, ?> response = sidecar.post()
                    .uri("/checkpoint/synthesize")
                    .body(Map.of("analysis", analysis))
                    .retrieve()
                    .body(Map.class);
            return response != null ? response.getOrDefault("result", "").toString() : "";
        } catch (Exception e) {
            return "Report: " + analysis;
        }
    }
}
