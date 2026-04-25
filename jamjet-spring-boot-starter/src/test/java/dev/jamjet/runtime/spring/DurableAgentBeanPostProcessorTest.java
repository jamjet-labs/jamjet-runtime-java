package dev.jamjet.runtime.spring;

import dev.jamjet.runtime.instrument.annotations.DurableAgent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DurableAgentBeanPostProcessor}.
 */
class DurableAgentBeanPostProcessorTest {

    @DurableAgent
    static class AnnotatedAgent {
        public void run() {}
    }

    @DurableAgent("my-named-agent")
    static class NamedAgent {
        public void run() {}
    }

    static class PlainBean {
        public void run() {}
    }

    @Test
    void detects_annotatedBeans() {
        DurableAgentBeanPostProcessor processor = new DurableAgentBeanPostProcessor();
        AnnotatedAgent bean = new AnnotatedAgent();

        processor.postProcessAfterInitialization(bean, "annotatedAgent");

        assertThat(processor.getDiscoveredAgents()).containsExactly("AnnotatedAgent");
    }

    @Test
    void detects_namedAnnotation() {
        DurableAgentBeanPostProcessor processor = new DurableAgentBeanPostProcessor();
        NamedAgent bean = new NamedAgent();

        processor.postProcessAfterInitialization(bean, "namedAgent");

        assertThat(processor.getDiscoveredAgents()).containsExactly("my-named-agent");
    }

    @Test
    void ignores_nonAnnotatedBeans() {
        DurableAgentBeanPostProcessor processor = new DurableAgentBeanPostProcessor();
        PlainBean bean = new PlainBean();

        processor.postProcessAfterInitialization(bean, "plainBean");

        assertThat(processor.getDiscoveredAgents()).isEmpty();
    }

    @Test
    void tracksMultipleBeans() {
        DurableAgentBeanPostProcessor processor = new DurableAgentBeanPostProcessor();

        processor.postProcessAfterInitialization(new AnnotatedAgent(), "a1");
        processor.postProcessAfterInitialization(new PlainBean(), "plain");
        processor.postProcessAfterInitialization(new NamedAgent(), "n1");

        assertThat(processor.getDiscoveredAgents())
                .hasSize(2)
                .containsExactlyInAnyOrder("AnnotatedAgent", "my-named-agent");
    }

    @Test
    void getDiscoveredAgents_isUnmodifiable() {
        DurableAgentBeanPostProcessor processor = new DurableAgentBeanPostProcessor();
        var agents = processor.getDiscoveredAgents();
        assertThat(agents).isEmpty();
        // Verify the returned list is unmodifiable
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> agents.add("should-fail")
        );
    }
}
