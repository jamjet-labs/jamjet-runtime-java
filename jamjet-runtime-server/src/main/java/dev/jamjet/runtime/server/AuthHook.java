package dev.jamjet.runtime.server;

import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Decides whether a request may reach the API. Called before every route except
 * {@code GET /health}. Return {@code false} to answer 401; an exception also answers 401.
 */
@FunctionalInterface
public interface AuthHook {

    boolean authorize(Context ctx);

    /**
     * Accepts only {@code Authorization: Bearer <token>} with exactly this token, compared in
     * constant time.
     */
    static AuthHook bearerToken(String token) {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) {
            throw new IllegalArgumentException("bearer token must not be blank");
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        return ctx -> {
            String header = ctx.header("Authorization");
            if (header == null || header.length() < 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return false;
            }
            byte[] presented = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, presented);
        };
    }
}
