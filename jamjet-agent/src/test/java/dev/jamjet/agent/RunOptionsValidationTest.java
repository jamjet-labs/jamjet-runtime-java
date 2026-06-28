package dev.jamjet.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation guards on {@link RunOptions}: the compact constructor already rejects a
 * non-positive {@code pollInterval}; it must equally reject a zero/negative
 * {@code timeout} (a {@code null} timeout is still allowed — it derives from the agent).
 */
class RunOptionsValidationTest {

    @Test
    void rejectsZeroTimeout() {
        assertThatThrownBy(() -> RunOptions.defaults().withTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void rejectsNegativeTimeout() {
        assertThatThrownBy(() -> RunOptions.defaults().withTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void allowsNullTimeout() {
        // null timeout = derive the deadline from the agent's timeoutSeconds.
        assertThatCode(() -> RunOptions.defaults().withTimeout(null)).doesNotThrowAnyException();
    }

    @Test
    void allowsPositiveTimeout() {
        assertThatCode(() -> RunOptions.defaults().withTimeout(Duration.ofSeconds(5)))
                .doesNotThrowAnyException();
    }
}
