package dev.jamjet.cloud.instrumentation.langchain4j;

import dev.jamjet.cloud.JamjetCloud;
import dev.jamjet.cloud.JamjetCloudConfig;
import dev.jamjet.cloud.Span;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequest;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponse;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetChatModelListenerTest {

    @BeforeEach
    void configure() {
        JamjetCloud.configure(JamjetCloudConfig.builder()
                .apiKey("jj_test")
                .apiUrl("http://127.0.0.1:1")
                .project("test")
                .captureIo(true)
                .build());
    }

    @Test
    void onResponsePopulatesSpan() {
        var listener = new JamjetChatModelListener();
        Map<Object, Object> attrs = new HashMap<>();

        var req = ChatModelRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(UserMessage.from("hi")))
                .build();
        listener.onRequest(new ChatModelRequestContext(req, attrs));

        Span span = (Span) attrs.get("jamjet.span");
        assertThat(span).isNotNull();

        var resp = ChatModelResponse.builder()
                .model("gpt-4o-mini")
                .tokenUsage(new TokenUsage(120, 80))
                .aiMessage(AiMessage.from("hello back"))
                .build();
        listener.onResponse(new ChatModelResponseContext(resp, req, attrs));

        var event = span.toEventMap();
        assertThat(event.get("kind")).isEqualTo("llm_call");
        assertThat(event.get("name")).isEqualTo("langchain4j.gpt-4o-mini");
        assertThat(event.get("input_tokens")).isEqualTo(120L);
        assertThat(event.get("output_tokens")).isEqualTo(80L);
        assertThat(event.get("status")).isEqualTo("ok");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload).containsKey("response");
    }

    @Test
    void redactOmitsPayload() {
        JamjetCloud.configure(JamjetCloudConfig.builder()
                .apiKey("jj_test")
                .apiUrl("http://127.0.0.1:1")
                .project("test")
                .captureIo(false)
                .build());

        var listener = new JamjetChatModelListener();
        Map<Object, Object> attrs = new HashMap<>();
        var req = ChatModelRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(UserMessage.from("secret")))
                .build();
        listener.onRequest(new ChatModelRequestContext(req, attrs));
        Span span = (Span) attrs.get("jamjet.span");
        var resp = ChatModelResponse.builder()
                .model("gpt-4o-mini")
                .tokenUsage(new TokenUsage(10, 10))
                .aiMessage(AiMessage.from("private"))
                .build();
        listener.onResponse(new ChatModelResponseContext(resp, req, attrs));

        var event = span.toEventMap();
        assertThat(event.get("payload")).isNull();
    }

    @Test
    void onErrorTagsFailureMode() {
        var listener = new JamjetChatModelListener();
        Map<Object, Object> attrs = new HashMap<>();
        var req = ChatModelRequest.builder().model("gpt-4o").messages(List.of(UserMessage.from("x"))).build();
        listener.onRequest(new ChatModelRequestContext(req, attrs));
        Span span = (Span) attrs.get("jamjet.span");
        listener.onError(new ChatModelErrorContext(new RuntimeException("HTTP 401 Unauthorized"), req, null, attrs));
        var event = span.toEventMap();
        assertThat(event.get("status")).isEqualTo("error");
        assertThat(event.get("failure_mode")).isEqualTo("auth");
        assertThat(event.get("payload")).isNull();
    }
}
