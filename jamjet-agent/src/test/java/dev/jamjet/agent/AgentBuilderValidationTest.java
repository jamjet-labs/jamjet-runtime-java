package dev.jamjet.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Input-validation guards on the {@link Agent.Builder}: a non-positive timeout and a
 * blank/empty model are rejected eagerly rather than producing a nonsensical IR.
 */
class AgentBuilderValidationTest {

    @Test
    void rejectsNonPositiveTimeoutSeconds() {
        assertThatThrownBy(() -> Agent.builder("a").timeoutSeconds(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutSeconds");
        assertThatThrownBy(() -> Agent.builder("a").timeoutSeconds(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutSeconds");
    }

    @Test
    void acceptsPositiveTimeoutSeconds() {
        Agent agent = Agent.builder("a").model("anthropic/claude-sonnet-4-6")
                .timeoutSeconds(120).build();
        assertThat(agent.timeoutSeconds()).isEqualTo(120);
    }

    @Test
    void rejectsBlankModel() {
        assertThatThrownBy(() -> Agent.builder("a").model("   ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(() -> Agent.builder("a").model("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void rejectsNullModel() {
        assertThatThrownBy(() -> Agent.builder("a").build())
                .isInstanceOf(NullPointerException.class);
    }
}
