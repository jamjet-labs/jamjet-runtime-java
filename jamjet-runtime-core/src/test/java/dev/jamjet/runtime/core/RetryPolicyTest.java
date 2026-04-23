package dev.jamjet.runtime.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final ObjectMapper mapper = JamjetJson.shared();

    @Test
    void exponentialDelays() {
        RetryPolicy policy = RetryPolicy.llmDefault();
        assertThat(policy.delayForAttempt(0)).isEqualTo(Duration.ZERO);
        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(8));
        // Attempt 6 would be 2*2^5 = 64s, capped at maxDelay of 60s
        assertThat(policy.delayForAttempt(6)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void linearDelays() {
        RetryPolicy policy = new RetryPolicy(
                3, BackoffStrategy.LINEAR,
                Duration.ofSeconds(5), Duration.ofSeconds(30),
                false, java.util.List.of()
        );
        assertThat(policy.delayForAttempt(0)).isEqualTo(Duration.ZERO);
        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(15));
        // Attempt 7 would be 5*7 = 35s, capped at 30s
        assertThat(policy.delayForAttempt(7)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void fixedDelays() {
        RetryPolicy policy = new RetryPolicy(
                3, BackoffStrategy.FIXED,
                Duration.ofSeconds(10), Duration.ofSeconds(10),
                false, java.util.List.of()
        );
        assertThat(policy.delayForAttempt(0)).isEqualTo(Duration.ZERO);
        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delayForAttempt(5)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void factoryMethods() {
        RetryPolicy io = RetryPolicy.ioDefault();
        assertThat(io.maxAttempts()).isEqualTo(3);
        assertThat(io.backoff()).isEqualTo(BackoffStrategy.EXPONENTIAL);
        assertThat(io.initialDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(io.maxDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(io.jitter()).isTrue();
        assertThat(io.retryableOn()).containsExactly(
                ErrorClass.IO_ERROR, ErrorClass.TIMEOUT, ErrorClass.CONNECTION_RESET
        );

        RetryPolicy llm = RetryPolicy.llmDefault();
        assertThat(llm.maxAttempts()).isEqualTo(3);
        assertThat(llm.initialDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(llm.maxDelay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(llm.retryableOn()).containsExactly(
                ErrorClass.RATE_LIMIT, ErrorClass.TIMEOUT, ErrorClass.SERVER_ERROR
        );

        RetryPolicy none = RetryPolicy.noRetry();
        assertThat(none.maxAttempts()).isEqualTo(1);
    }

    @Test
    void jsonRoundTrip() throws JsonProcessingException {
        RetryPolicy original = RetryPolicy.ioDefault();
        String json = mapper.writeValueAsString(original);

        // Verify snake_case keys and seconds-based duration
        assertThat(json).contains("\"max_attempts\":3");
        assertThat(json).contains("\"initial_delay\":1");
        assertThat(json).contains("\"max_delay\":30");
        assertThat(json).contains("\"backoff\":\"exponential\"");

        RetryPolicy deserialized = mapper.readValue(json, RetryPolicy.class);
        assertThat(deserialized).isEqualTo(original);
    }
}
