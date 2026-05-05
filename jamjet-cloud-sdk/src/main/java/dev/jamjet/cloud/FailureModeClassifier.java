package dev.jamjet.cloud;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Maps a Throwable to a string failure_mode that matches the cloud's
 * events_failure_mode_check constraint and the Python SDK enum.
 *
 * <p>Cross-SDK contract: same string values for rate_limit, auth, timeout,
 * bad_request, server_error, unknown.
 */
public final class FailureModeClassifier {

    private static final Pattern STATUS_4XX_5XX = Pattern.compile("(?i)\\b(?:HTTP\\s+|status(?:\\s+code)?\\s*:?\\s*)?([45]\\d{2})\\b");

    private FailureModeClassifier() {}

    public static String classify(Throwable t) {
        return classify(t, null);
    }

    public static String classify(Throwable t, Duration ignored) {
        if (t == null) return "unknown";
        if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
            return "timeout";
        }
        String msg = t.getMessage();
        if (msg != null) {
            var m = STATUS_4XX_5XX.matcher(msg);
            if (m.find()) {
                int status = Integer.parseInt(m.group(1));
                if (status == 429) return "rate_limit";
                if (status == 401 || status == 403) return "auth";
                if (status >= 400 && status < 500) return "bad_request";
                if (status >= 500) return "server_error";
            }
        }
        return "unknown";
    }
}
