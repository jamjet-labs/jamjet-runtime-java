package dev.jamjet.cloud.spring;

import dev.jamjet.cloud.JamjetCloud;
import dev.jamjet.cloud.JamjetCloudConfig;
import dev.jamjet.cloud.instrumentation.langchain4j.JamjetChatModelListener;
import dev.jamjet.cloud.instrumentation.spring.JamjetObservationHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "jamjet.cloud", name = "api-key")
@EnableConfigurationProperties(JamjetCloudProperties.class)
public class JamjetCloudAutoConfiguration {

    @Bean
    JamjetCloudInitialized jamjetCloudInitialized(JamjetCloudProperties p) {
        var builder = JamjetCloudConfig.builder()
                .apiKey(p.getApiKey())
                .apiUrl(p.getApiUrl())
                .project(p.getProject())
                .captureIo(p.isCaptureIo())
                .autoPatch(p.isAutoPatch())
                .flushIntervalMs(p.getBatch().getIntervalMs())
                .flushSize(p.getBatch().getSize());
        if (p.getAgent().getName() != null) {
            builder.agentName(p.getAgent().getName());
        }
        JamjetCloud.configure(builder.build());
        return new JamjetCloudInitialized();
    }

    @Bean
    @ConditionalOnClass(io.micrometer.observation.ObservationHandler.class)
    @ConditionalOnProperty(prefix = "jamjet.cloud", name = "auto-patch", matchIfMissing = true)
    JamjetObservationHandler jamjetObservationHandler(JamjetCloudInitialized init) {
        return new JamjetObservationHandler();
    }

    @Bean
    @ConditionalOnClass(name = "dev.langchain4j.model.chat.listener.ChatModelListener")
    @ConditionalOnProperty(prefix = "jamjet.cloud", name = "auto-patch", matchIfMissing = true)
    JamjetChatModelListener jamjetChatModelListener(JamjetCloudInitialized init) {
        return new JamjetChatModelListener();
    }

    @Bean
    @ConditionalOnClass(name = "dev.langchain4j.model.chat.listener.ChatModelListener")
    @ConditionalOnProperty(prefix = "jamjet.cloud", name = "auto-patch", matchIfMissing = true)
    ChatModelListenerPostProcessor chatModelListenerPostProcessor(JamjetChatModelListener listener) {
        return new ChatModelListenerPostProcessor(listener);
    }

    public static final class JamjetCloudInitialized {}
}
