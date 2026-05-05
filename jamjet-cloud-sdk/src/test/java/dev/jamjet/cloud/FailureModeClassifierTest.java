package dev.jamjet.cloud;

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureModeClassifierTest {

    @Test
    void classifiesTimeouts() {
        assertEquals("timeout", FailureModeClassifier.classify(new SocketTimeoutException("read timed out")));
        assertEquals("timeout", FailureModeClassifier.classify(HttpTimeoutException.class.cast(
                new HttpTimeoutException("request timed out"))));
    }

    @Test
    void classifiesByMessageHttpStatus() {
        assertEquals("rate_limit", FailureModeClassifier.classify(new RuntimeException("HTTP 429 Too Many Requests")));
        assertEquals("auth", FailureModeClassifier.classify(new RuntimeException("status 401 unauthorized")));
        assertEquals("auth", FailureModeClassifier.classify(new RuntimeException("403 forbidden")));
        assertEquals("bad_request", FailureModeClassifier.classify(new RuntimeException("status code: 400")));
        assertEquals("server_error", FailureModeClassifier.classify(new RuntimeException("HTTP 503")));
    }

    @Test
    void unknownByDefault() {
        assertEquals("unknown", FailureModeClassifier.classify(new IllegalStateException("boom")));
        assertEquals("unknown", FailureModeClassifier.classify(null));
    }

    @Test
    void durationToleranceUsed() {
        assertEquals("timeout", FailureModeClassifier.classify(new SocketTimeoutException(),
                Duration.ofSeconds(30)));
    }
}
