package dev.jamjet.example.cloud;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.jamjet.cloud.JamjetCloudConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class PlainJavaCloudDemoTest {

    WireMockServer wireMock;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        wireMock.stubFor(post(urlEqualTo("/v1/events/ingest")).willReturn(ok()));
    }

    @AfterEach
    void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void emitsSpanToCloudAndSchemaValidReceiptFile(@TempDir Path dir) throws Exception {
        Path receipts = dir.resolve("receipts.jsonl");
        JamjetCloudConfig cfg = JamjetCloudConfig.builder()
                .apiKey("test-key")
                .apiUrl(wireMock.baseUrl())
                .project("cloud-sdk-demo")
                .flushSize(1)
                .flushIntervalMs(100)
                .build();

        PlainJavaCloudDemo.run(cfg, new FileActionReceiptEmitter(receipts));

        // span emission is async (no public flush); poll WireMock up to ~5s
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        boolean gotSpan = false;
        while (System.nanoTime() < deadline) {
            if (!wireMock.findAll(postRequestedFor(urlEqualTo("/v1/events/ingest"))).isEmpty()) {
                gotSpan = true;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(gotSpan).as("a span was POSTed to /v1/events/ingest").isTrue();

        // receipt written and schema-valid
        var lines = Files.readAllLines(receipts);
        assertThat(lines).hasSize(1);
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(lines.get(0), java.util.Map.class);
        @SuppressWarnings("unchecked")
        var errors = new dev.jamjet.cloud.agentboundary.ActionReceiptValidator()
                .validate((java.util.Map<String, Object>) parsed);
        assertThat(errors).isEmpty();
    }
}
