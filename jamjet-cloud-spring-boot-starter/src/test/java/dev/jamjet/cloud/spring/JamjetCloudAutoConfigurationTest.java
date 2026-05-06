package dev.jamjet.cloud.spring;

import dev.jamjet.cloud.JamjetCloud;
import dev.jamjet.cloud.instrumentation.langchain4j.JamjetChatModelListener;
import dev.jamjet.cloud.instrumentation.spring.JamjetObservationHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetCloudAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetCloudAutoConfiguration.class));

    @Test
    void skipsAutoConfigWhenApiKeyMissing() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(JamjetObservationHandler.class);
            assertThat(ctx).doesNotHaveBean(JamjetChatModelListener.class);
        });
    }

    @Test
    void registersBeansWhenApiKeyPresent() {
        runner.withPropertyValues("jamjet.cloud.api-key=jj_test",
                                   "jamjet.cloud.api-url=http://127.0.0.1:1")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JamjetObservationHandler.class);
                    assertThat(ctx).hasSingleBean(JamjetChatModelListener.class);
                    assertThat(JamjetCloud.config()).isNotNull();
                    assertThat(JamjetCloud.config().apiKey()).isEqualTo("jj_test");
                });
    }

    @Test
    void disableViaAutoPatchProperty() {
        runner.withPropertyValues("jamjet.cloud.api-key=jj_test",
                                   "jamjet.cloud.api-url=http://127.0.0.1:1",
                                   "jamjet.cloud.auto-patch=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(JamjetObservationHandler.class);
                    assertThat(ctx).doesNotHaveBean(JamjetChatModelListener.class);
                });
    }

    @Test
    void contextStartsWithoutLlmLibsOnClasspath() {
        runner.withPropertyValues("jamjet.cloud.api-key=jj_test",
                                   "jamjet.cloud.api-url=http://127.0.0.1:1")
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        io.micrometer.observation.ObservationHandler.class,
                        dev.langchain4j.model.chat.listener.ChatModelListener.class))
                .run(ctx -> {
                    assertThat(JamjetCloud.config()).isNotNull();
                    assertThat(ctx).doesNotHaveBean(JamjetObservationHandler.class);
                    assertThat(ctx).doesNotHaveBean(JamjetChatModelListener.class);
                });
    }
}
