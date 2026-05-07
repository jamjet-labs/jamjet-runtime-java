package dev.jamjet.demo.springaiengram;

import dev.jamjet.demo.springaiengram.startup.PreflightCheck;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class PreflightCheckTest {

    @Test
    void rejectsMissingOpenAiKey() {
        PreflightCheck check = new PreflightCheck(
                Map.of("JAMJET_API_KEY", "jk_test"),
                "http://localhost:65535/health" // unreachable; we shouldn't get past env check
        );

        Throwable thrown = catchThrowable(() -> check.validateEnv());

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY")
                .hasMessageContaining("platform.openai.com");
    }

    @Test
    void rejectsMissingJamjetKey() {
        PreflightCheck check = new PreflightCheck(
                Map.of("OPENAI_API_KEY", "sk-test"),
                "http://localhost:65535/health"
        );

        Throwable thrown = catchThrowable(() -> check.validateEnv());

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JAMJET_API_KEY")
                .hasMessageContaining("cloud.jamjet.dev");
    }

    @Test
    void acceptsBothKeysPresent() {
        PreflightCheck check = new PreflightCheck(
                Map.of("OPENAI_API_KEY", "sk-test", "JAMJET_API_KEY", "jk_test"),
                "http://localhost:65535/health"
        );

        // No throw expected.
        check.validateEnv();
    }
}
