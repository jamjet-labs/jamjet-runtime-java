package dev.jamjet.demo.springaiengram;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.jamjet.cloud.spring.JamjetCloudAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Testcontainers
// JamjetCloudAutoConfiguration 0.2.0 references langchain4j which is not on the test classpath;
// exclude it to avoid ClassNotFoundException during condition processing.
@EnableAutoConfiguration(exclude = JamjetCloudAutoConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoIntegrationTest {

    static WireMockServer wireMock;

    @Container
    static GenericContainer<?> engram = new GenericContainer<>(
            DockerImageName.parse("ghcr.io/jamjet-labs/engram-server:0.5.0"))
            .withExposedPorts(9090)
            .withEnv("ENGRAM_MODE", "rest")
            .withEnv("ENGRAM_LLM_PROVIDER", "mock")
            .withEnv("ENGRAM_EMBEDDING_PROVIDER", "mock")
            .waitingFor(Wait.forHttp("/health").forStatusCode(200));

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        // Stub a single OpenAI chat completion that returns "Acme".
        wireMock.stubFor(WireMock.post(WireMock.urlMatching("/v1/chat/completions.*"))
                .willReturn(WireMock.okJson("""
                        {
                          "id": "chatcmpl-test",
                          "object": "chat.completion",
                          "model": "gpt-4o-mini",
                          "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "Acme"},
                            "finish_reason": "stop"
                          }],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 1, "total_tokens": 11}
                        }
                        """)));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> wireMock.baseUrl());
        registry.add("spring.ai.openai.api-key", () -> "sk-test");
        registry.add("engram.base-url",
                () -> "http://" + engram.getHost() + ":" + engram.getMappedPort(9090));
        registry.add("jamjet.cloud.api-key", () -> "jk_test");
        registry.add("jamjet.cloud.api-url", () -> "http://localhost:1");
        registry.add("app.engram.health-url",
                () -> "http://" + engram.getHost() + ":" + engram.getMappedPort(9090) + "/health");
    }

    @LocalServerPort
    int port;

    @Autowired
    RestTemplateBuilder restTemplateBuilder;

    @Test
    void chatEndpointReturnsAgentReply() {
        RestTemplate rt = restTemplateBuilder.build();
        String body = rt.postForObject(
                "http://127.0.0.1:" + port + "/chat?session=alice",
                "Where do I work?",
                String.class);
        assertThat(body).contains("Acme");
    }
}
