package dev.jamjet.cloud.instrumentation.spring;

import dev.jamjet.cloud.FailureModeClassifier;
import dev.jamjet.cloud.JamjetCloud;
import dev.jamjet.cloud.Span;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges Spring AI Micrometer observations to the JamJet Cloud event stream.
 *
 * <p>Filters on observations whose name starts with {@code gen_ai.client}.
 * Spring AI emits these for every {@code ChatModel} and {@code ChatClient}
 * call (Spring AI 1.0+). Reads model + token counts from Micrometer
 * KeyValues, opens a Span on start, finishes it on stop.
 */
public final class JamjetObservationHandler implements ObservationHandler<Observation.Context> {

    private static final Logger LOG = LoggerFactory.getLogger(JamjetObservationHandler.class);
    private static final String SCOPE_KEY = "jamjet.span";

    @Override
    public boolean supportsContext(Observation.Context ctx) {
        return ctx != null && ctx.getName() != null && ctx.getName().startsWith("gen_ai.client");
    }

    @Override
    public void onStart(Observation.Context ctx) {
        try {
            String model = readKey(ctx, "gen_ai.request.model");
            String provider = readKeyOr(ctx, "gen_ai.system", "unknown");
            String name = (model != null) ? provider + "." + model : provider + ".chat";
            Span span = JamjetCloud.newSpan("llm_call", name);
            ctx.put(SCOPE_KEY, span);
        } catch (Throwable t) {
            LOG.warn("JamjetObservationHandler.onStart failed", t);
        }
    }

    @Override
    public void onStop(Observation.Context ctx) {
        Span span = (Span) ctx.get(SCOPE_KEY);
        if (span == null) return;
        try {
            String model = readKey(ctx, "gen_ai.request.model");
            if (model != null) span.model(model);
            Long in = readLong(ctx, "gen_ai.usage.input_tokens");
            Long out = readLong(ctx, "gen_ai.usage.output_tokens");
            if (in != null) span.inputTokens(in);
            if (out != null) span.outputTokens(out);

            if (ctx.getError() != null) {
                span.fail(FailureModeClassifier.classify(ctx.getError()));
            } else {
                span.finish("ok");
            }
        } catch (Throwable t) {
            LOG.warn("JamjetObservationHandler.onStop failed", t);
            try { span.finish("error"); } catch (Throwable ignored) {}
        }
    }

    private static String readKey(Observation.Context ctx, String key) {
        for (KeyValue kv : ctx.getLowCardinalityKeyValues()) {
            if (key.equals(kv.getKey())) return kv.getValue();
        }
        for (KeyValue kv : ctx.getHighCardinalityKeyValues()) {
            if (key.equals(kv.getKey())) return kv.getValue();
        }
        return null;
    }

    private static String readKeyOr(Observation.Context ctx, String key, String fallback) {
        String v = readKey(ctx, key);
        return v != null ? v : fallback;
    }

    private static Long readLong(Observation.Context ctx, String key) {
        String v = readKey(ctx, key);
        if (v == null) return null;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return null; }
    }
}
