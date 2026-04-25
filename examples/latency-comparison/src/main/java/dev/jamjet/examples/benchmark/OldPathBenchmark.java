package dev.jamjet.examples.benchmark;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Measures end-to-end latency for a 3-node workflow executed via HTTP calls to a WireMock
 * stub, simulating a REST sidecar deployment. This is the "old path" — every runtime
 * operation crosses a network boundary even though both client and server are in the same JVM.
 *
 * <p>Stubs:</p>
 * <ul>
 *   <li>POST /workflows            -&gt; 201 (register workflow)</li>
 *   <li>POST /executions           -&gt; 201 (create execution)</li>
 *   <li>POST /work-items           -&gt; 201 (enqueue work item)</li>
 *   <li>POST /work-items/claim     -&gt; 200 (claim work item)</li>
 *   <li>POST /work-items/{id}/complete -&gt; 200 (complete work item)</li>
 * </ul>
 */
public class OldPathBenchmark {

    private static final String EMPTY_JSON = "{}";
    private static final String WORK_ITEM_RESPONSE =
            "{\"id\":\"" + UUID.randomUUID() + "\",\"nodeId\":\"fetch\",\"queueType\":\"tool\",\"attempt\":1}";

    private WireMockServer wireMock;
    private HttpClient httpClient;
    private String baseUrl;

    public OldPathBenchmark() {
    }

    /**
     * Starts WireMock on a random available port and registers all stub routes.
     */
    public void setup() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
        httpClient = HttpClient.newHttpClient();
        registerStubs();
    }

    /**
     * Returns the base URL of the running WireMock server.
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Stops WireMock.
     */
    public void teardown() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    /**
     * Runs the benchmark.
     *
     * @param warmup     number of warmup iterations (not measured)
     * @param iterations number of measured iterations
     * @return list of per-workflow latency samples in nanoseconds
     */
    public List<Long> run(int warmup, int iterations) throws IOException, InterruptedException {
        for (int i = 0; i < warmup; i++) {
            runSingleWorkflow();
        }
        List<Long> samples = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            runSingleWorkflow();
            samples.add(System.nanoTime() - start);
        }
        return samples;
    }

    /**
     * Simulates a 3-node workflow via HTTP: register workflow, create execution, then for
     * each of 3 nodes: enqueue -&gt; claim -&gt; complete.
     */
    private void runSingleWorkflow() throws IOException, InterruptedException {
        httpPost(baseUrl + "/workflows", EMPTY_JSON);
        httpPost(baseUrl + "/executions", EMPTY_JSON);
        for (int node = 0; node < 3; node++) {
            String itemId = UUID.randomUUID().toString();
            httpPost(baseUrl + "/work-items", EMPTY_JSON);
            httpPost(baseUrl + "/work-items/claim", EMPTY_JSON);
            httpPost(baseUrl + "/work-items/" + itemId + "/complete", EMPTY_JSON);
        }
    }

    private void httpPost(String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private void registerStubs() {
        wireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/workflows"))
                .willReturn(WireMock.aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EMPTY_JSON)));

        wireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/executions"))
                .willReturn(WireMock.aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EMPTY_JSON)));

        wireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/work-items"))
                .willReturn(WireMock.aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EMPTY_JSON)));

        wireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/work-items/claim"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(WORK_ITEM_RESPONSE)));

        wireMock.stubFor(WireMock.post(WireMock.urlMatching("/work-items/.*/complete"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EMPTY_JSON)));
    }
}
