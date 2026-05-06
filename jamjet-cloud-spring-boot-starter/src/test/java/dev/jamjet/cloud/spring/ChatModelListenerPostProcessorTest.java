package dev.jamjet.cloud.spring;

import dev.jamjet.cloud.instrumentation.langchain4j.JamjetChatModelListener;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelListenerPostProcessorTest {

    static class ModelWithListenersField implements ChatLanguageModel {
        final List<ChatModelListener> listeners = new ArrayList<>();
        @Override public Response<AiMessage> generate(List<ChatMessage> messages) {
            return Response.from(AiMessage.from("ok"));
        }
    }

    static class ModelWithoutListeners implements ChatLanguageModel {
        @Override public Response<AiMessage> generate(List<ChatMessage> messages) {
            return Response.from(AiMessage.from("ok"));
        }
    }

    @Test
    void appendsListenerToModelWithListenersField() {
        var bpp = new ChatModelListenerPostProcessor(new JamjetChatModelListener());
        var model = new ModelWithListenersField();
        Object out = bpp.postProcessAfterInitialization(model, "model");
        assertThat(out).isSameAs(model);
        assertThat(model.listeners).hasSize(1).first().isInstanceOf(JamjetChatModelListener.class);
    }

    @Test
    void noOpForModelWithoutListenersField() {
        var bpp = new ChatModelListenerPostProcessor(new JamjetChatModelListener());
        var model = new ModelWithoutListeners();
        Object out = bpp.postProcessAfterInitialization(model, "model");
        assertThat(out).isSameAs(model);
    }

    @Test
    void leavesNonChatModelBeansAlone() {
        var bpp = new ChatModelListenerPostProcessor(new JamjetChatModelListener());
        Object random = "hello";
        assertThat(bpp.postProcessAfterInitialization(random, "x")).isSameAs(random);
    }
}
