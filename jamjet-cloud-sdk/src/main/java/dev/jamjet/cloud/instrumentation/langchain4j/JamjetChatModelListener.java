package dev.jamjet.cloud.instrumentation.langchain4j;

import dev.jamjet.cloud.FailureModeClassifier;
import dev.jamjet.cloud.JamjetCloud;
import dev.jamjet.cloud.JamjetCloudConfig;
import dev.jamjet.cloud.Span;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges LangChain4j {@code ChatModelListener} callbacks to the JamJet
 * Cloud event stream. Mirrors {@code JamjetObservationHandler} for the
 * Spring AI path — same kind ("llm_call"), same payload shape.
 *
 * <p>Honors {@link JamjetCloudConfig#captureIo()}: when false, prompt and
 * response payloads are omitted (server-side redaction is the authoritative
 * backstop on Team+ tier).
 */
public final class JamjetChatModelListener implements ChatModelListener {

    private static final Logger LOG = LoggerFactory.getLogger(JamjetChatModelListener.class);
    private static final String SCOPE_KEY = "jamjet.span";

    @Override
    public void onRequest(ChatModelRequestContext rc) {
        try {
            String model = rc.request().model();
            String name = "langchain4j." + (model != null ? model : "chat");
            Span span = JamjetCloud.newSpan("llm_call", name);
            if (model != null) span.model(model);
            if (captureIo()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("messages", rc.request().messages());
                span.payload(p);
            }
            rc.attributes().put(SCOPE_KEY, span);
        } catch (Throwable t) {
            LOG.warn("JamjetChatModelListener.onRequest failed", t);
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext rc) {
        Span span = (Span) rc.attributes().get(SCOPE_KEY);
        if (span == null) return;
        try {
            var usage = rc.response().tokenUsage();
            if (usage != null) {
                if (usage.inputTokenCount() != null) span.inputTokens(usage.inputTokenCount());
                if (usage.outputTokenCount() != null) span.outputTokens(usage.outputTokenCount());
            }
            if (captureIo()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) span.payload();
                if (p == null) {
                    p = new LinkedHashMap<>();
                    span.payload(p);
                }
                if (rc.response().aiMessage() != null) {
                    p.put("response", rc.response().aiMessage().text());
                }
            }
            span.finish("ok");
        } catch (Throwable t) {
            LOG.warn("JamjetChatModelListener.onResponse failed", t);
            try { span.finish("error"); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onError(ChatModelErrorContext rc) {
        Span span = (Span) rc.attributes().get(SCOPE_KEY);
        if (span == null) return;
        try {
            span.payload(null);
            span.fail(FailureModeClassifier.classify(rc.error()));
        } catch (Throwable t) {
            LOG.warn("JamjetChatModelListener.onError failed", t);
            try { span.finish("error"); } catch (Throwable ignored) {}
        }
    }

    private static boolean captureIo() {
        JamjetCloudConfig cfg = JamjetCloud.config();
        return cfg != null && cfg.captureIo();
    }
}
