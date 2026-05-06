package dev.jamjet.cloud.instrumentation.langchain4j;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.jamjet.cloud.JamjetCloud;
import dev.jamjet.cloud.JamjetCloudConfig;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;

class LangChain4jIntegrationTest {

    static WireMockServer openai = new WireMockServer(options().dynamicPort());
    static WireMockServer cloud  = new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void up() {
        openai.start();
        cloud.start();
        openai.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson("""
                        {
                          "id": "chatcmpl-1",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o-mini",
                          "choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"}, "finish_reason": "stop"}],
                          "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7}
                        }
                        """)));
        cloud.stubFor(post(urlEqualTo("/v1/events/ingest"))
                .willReturn(okJson("{\"accepted\": 1, \"trace_ids\": [\"tr_x\"]}")));

        JamjetCloud.configure(JamjetCloudConfig.builder()
                .apiKey("jj_test")
                .apiUrl(cloud.baseUrl())
                .project("test")
                .flushIntervalMs(200)
                .build());
    }

    @AfterAll
    static void down() {
        openai.stop();
        cloud.stop();
    }

    @Test
    void manualWiringEmitsSpan() {
        var model = OpenAiChatModel.builder()
                .apiKey("sk-test")
                .baseUrl(openai.baseUrl() + "/v1")
                .modelName("gpt-4o-mini")
                .listeners(List.of(new JamjetChatModelListener()))
                .build();

        model.generate(List.of(UserMessage.from("hi")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                cloud.verify(postRequestedFor(urlEqualTo("/v1/events/ingest"))
                        .withRequestBody(matchingJsonPath("$.events[0].kind", equalTo("llm_call")))
                        .withRequestBody(matchingJsonPath("$.events[0].name", equalTo("langchain4j.gpt-4o-mini")))
                        .withRequestBody(matchingJsonPath("$.events[0].input_tokens", equalTo("5")))
                        .withRequestBody(matchingJsonPath("$.events[0].output_tokens", equalTo("2"))))
        );
    }
}
