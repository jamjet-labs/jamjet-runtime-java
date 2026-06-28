package dev.jamjet.agent.client;

/**
 * Raised when an engine call fails — either a non-2xx HTTP response (carrying the
 * status code + raw body) or a transport-level error (carrying a cause).
 *
 * <p>The status code is surfaced so callers can branch on it. In particular a
 * {@code 409 Conflict} on {@code POST /work-items/{id}/complete} means a stale
 * lease fence (the item was reclaimed): the durable tool-worker must treat this
 * as a lost lease, not a generic failure. Use {@link #isConflict()} for that
 * branch.
 */
public final class JamjetHttpException extends RuntimeException {

    /** -1 when the failure is transport-level (no HTTP status was received). */
    private final int statusCode;

    /** The raw response body for a non-2xx; {@code null} for transport errors. */
    private final String body;

    /** A non-2xx HTTP response. */
    public JamjetHttpException(int statusCode, String body) {
        super("engine returned HTTP " + statusCode + (body == null || body.isBlank() ? "" : ": " + body));
        this.statusCode = statusCode;
        this.body = body;
    }

    /** A transport-level failure (timeout, connection refused, serialization, ...). */
    public JamjetHttpException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.body = null;
    }

    /** The HTTP status code, or -1 for a transport-level error. */
    public int statusCode() {
        return statusCode;
    }

    /** The raw response body for a non-2xx, or {@code null} for a transport error. */
    public String body() {
        return body;
    }

    /** True iff this is a {@code 409 Conflict} (e.g. a stale lease fence on complete). */
    public boolean isConflict() {
        return statusCode == 409;
    }
}
