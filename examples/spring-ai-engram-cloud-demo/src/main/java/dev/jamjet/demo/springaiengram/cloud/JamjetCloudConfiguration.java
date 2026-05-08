package dev.jamjet.demo.springaiengram.cloud;

import dev.jamjet.cloud.JamjetCloud;
import dev.jamjet.cloud.JamjetCloudConfig;
import dev.jamjet.cloud.instrumentation.spring.JamjetObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Wires JamJet Cloud observability into the demo.
 *
 * <p>We deliberately do NOT use jamjet-cloud-spring-boot-starter:0.2.0 because its
 * autoconfig hard-references LangChain4j classes — would force langchain4j-core onto
 * the classpath of a Spring-AI-only app. Instead we configure the SDK directly and
 * register the Spring AI observation handler explicitly with the ObservationRegistry.
 *
 * <p>This pattern can be upstreamed as a "Spring AI only" autoconfig in a future
 * cloud starter release.
 */
@Configuration
public class JamjetCloudConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(JamjetCloudConfiguration.class);

    private final String apiKey;
    private final String apiUrl;
    private final ObservationRegistry observationRegistry;

    public JamjetCloudConfiguration(
            @Value("${jamjet.cloud.api-key}") String apiKey,
            @Value("${jamjet.cloud.api-url:https://api.jamjet.dev}") String apiUrl,
            ObservationRegistry observationRegistry) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.observationRegistry = observationRegistry;
    }

    @PostConstruct
    void initJamjetCloud() {
        JamjetCloud.configure(JamjetCloudConfig.builder()
                .apiKey(apiKey)
                .apiUrl(apiUrl)
                .agentName("spring-ai-engram-demo")
                .build());

        // Workaround for jamjet-cloud-spring-boot-starter:0.2.0:
        //   1. Its autoconfig hard-references LangChain4j classes — would force langchain4j-core
        //      onto the classpath of a Spring-AI-only app. We bypass it and wire ourselves.
        //   2. Its JamjetObservationHandler.supportsContext filters by `name.startsWith("gen_ai.client")`,
        //      but Micrometer calls supportsContext while the context name is still null —
        //      the handler would never be selected. We use a context-class filter instead so the
        //      decision is name-independent.
        // Both are filed as upstream followups; remove this @Configuration once they ship.
        JamjetObservationHandler jamjet = new JamjetObservationHandler();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context ctx) {
                return ctx != null
                        && ctx.getClass().getName().equals("org.springframework.ai.chat.observation.ChatModelObservationContext");
            }
            @Override public void onStart(Observation.Context ctx) { jamjet.onStart(ctx); }
            @Override public void onStop(Observation.Context ctx) { jamjet.onStop(ctx); }
        });

        LOG.info("JamJet Cloud configured -> {} (agent=spring-ai-engram-demo)", apiUrl);
    }
}
