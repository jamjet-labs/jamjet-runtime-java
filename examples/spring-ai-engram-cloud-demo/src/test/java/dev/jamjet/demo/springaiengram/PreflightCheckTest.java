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

    @Test
    void waitsForEngramHealth_succeedsImmediately() {
        com.github.tomakehurst.wiremock.WireMockServer wm =
                new com.github.tomakehurst.wiremock.WireMockServer(0);
        wm.start();
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/health")
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.ok()));
        try {
            PreflightCheck check = new PreflightCheck(
                    Map.of("OPENAI_API_KEY", "sk", "JAMJET_API_KEY", "jk"),
                    wm.baseUrl() + "/health"
            );
            check.waitForEngram(); // should not throw
        } finally {
            wm.stop();
        }
    }

    @Test
    void waitsForEngramHealth_timesOut() {
        PreflightCheck check = new PreflightCheck(
                Map.of("OPENAI_API_KEY", "sk", "JAMJET_API_KEY", "jk"),
                "http://127.0.0.1:65535/health" // nothing listening
        );

        Throwable thrown = catchThrowable(() -> check.waitForEngramWithTimeout(java.time.Duration.ofSeconds(2)));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Engram")
                .hasMessageContaining("docker compose up");
    }
}
