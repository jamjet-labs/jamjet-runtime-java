package dev.jamjet.cloud.instrumentation.spring;

import dev.jamjet.cloud.JamjetCloud;
import dev.jamjet.cloud.JamjetCloudConfig;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetObservationHandlerTest {

    @BeforeEach
    void configure() {
        JamjetCloud.configure(JamjetCloudConfig.builder()
                .apiKey("jj_test")
                .apiUrl("http://127.0.0.1:1")
                .project("test")
                .build());
    }

    @Test
    void supportsContextOnlyMatchesGenAiClient() {
        var handler = new JamjetObservationHandler();
        assertThat(handler.supportsContext(ctx("gen_ai.client.operation"))).isTrue();
        assertThat(handler.supportsContext(ctx("http.client.requests"))).isFalse();
        assertThat(handler.supportsContext(ctx(null))).isFalse();
    }

    @Test
    void onStopPopulatesSpanFromKeyValues() {
        var handler = new JamjetObservationHandler();
        Observation.Context c = ctx("gen_ai.client.operation");
        c.addLowCardinalityKeyValues(KeyValues.of(
                "gen_ai.system", "openai",
                "gen_ai.request.model", "gpt-4o-mini"));
        c.addHighCardinalityKeyValues(KeyValues.of(
                "gen_ai.usage.input_tokens", "120",
                "gen_ai.usage.output_tokens", "80"));

        handler.onStart(c);
        handler.onStop(c);

        var span = (dev.jamjet.cloud.Span) c.get("jamjet.span");
        var event = span.toEventMap();
        assertThat(event.get("kind")).isEqualTo("llm_call");
        assertThat(event.get("name")).isEqualTo("openai.gpt-4o-mini");
        assertThat(event.get("model")).isEqualTo("gpt-4o-mini");
        assertThat(event.get("input_tokens")).isEqualTo(120L);
        assertThat(event.get("output_tokens")).isEqualTo(80L);
        assertThat(event.get("status")).isEqualTo("ok");
    }

    @Test
    void onStopRecordsErrorWithFailureMode() {
        var handler = new JamjetObservationHandler();
        Observation.Context c = ctx("gen_ai.client.operation");
        c.addLowCardinalityKeyValues(KeyValues.of("gen_ai.request.model", "gpt-4o"));
        handler.onStart(c);
        c.setError(new RuntimeException("HTTP 429 Too Many Requests"));
        handler.onStop(c);

        var span = (dev.jamjet.cloud.Span) c.get("jamjet.span");
        var event = span.toEventMap();
        assertThat(event.get("status")).isEqualTo("error");
        assertThat(event.get("failure_mode")).isEqualTo("rate_limit");
    }

    private static Observation.Context ctx(String name) {
        Observation.Context c = new Observation.Context();
        if (name != null) c.setName(name);
        return c;
    }
}
