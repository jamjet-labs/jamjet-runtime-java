package dev.jamjet.agent.spring;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.client.JamjetHttpException;
import dev.jamjet.runtime.core.JamjetJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Lets a person decide a parked approval through the application:
 * {@code GET {path}/executions/{id}/approvals} and {@code POST {path}/executions/{id}/approve}.
 * Bodies and responses have the engine's shape (snake_case).
 *
 * <p><strong>Authentication is the application's job.</strong> The endpoint serves only a
 * request with an authenticated {@link Principal} (Spring Security or the servlet container);
 * anything else gets 401. It never reads or forwards the caller's {@code Authorization}
 * header. At the engine it acts with the token {@link ApproverTokenResolver} gives for that
 * principal; a principal with no token gets 403.</p>
 *
 * <p>It refuses (503) to relay anything to an engine that does not require authentication
 * (dev mode): there every caller is anonymous and an approval is recorded under whatever name
 * the request carries. It checks on each request with an anonymous
 * {@code GET /executions?limit=1}.</p>
 *
 * <p>An {@code approved} decision must carry the {@code action_digest} the reviewer was shown
 * (from the pending request's {@code context}); the engine refuses a stale one with 409. The
 * endpoint refuses an approval without one (400). An execution id must match
 * {@code [A-Za-z0-9_-]{1,128}} (400 otherwise).</p>
 */
@RestController
@RequestMapping("${jamjet.agent.approvals.endpoint.path:/jamjet}")
public class JamjetApprovalController {

    private static final Logger log = LoggerFactory.getLogger(JamjetApprovalController.class);
    private static final Pattern EXECUTION_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final JamjetEngineClient engine;
    private final ApproverTokenResolver tokens;

    public JamjetApprovalController(JamjetEngineClient engine, ApproverTokenResolver tokens) {
        this.engine = engine;
        this.tokens = tokens;
    }

    /** The decision body, as the engine takes it. */
    public record DecisionRequest(
            @JsonProperty("decision") String decision,
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("action_digest") String actionDigest,
            @JsonProperty("comment") String comment) {
    }

    @GetMapping("/executions/{executionId}/approvals")
    public ResponseEntity<String> list(@PathVariable("executionId") String executionId, Principal principal) {
        return asApprover(principal, executionId, client -> forward(() -> client.listApprovals(executionId)));
    }

    @PostMapping("/executions/{executionId}/approve")
    public ResponseEntity<String> decide(
            @PathVariable("executionId") String executionId,
            @RequestBody(required = false) DecisionRequest body,
            Principal principal) {
        return asApprover(principal, executionId, client -> {
            if (body == null || blank(body.decision())) {
                return error(HttpStatus.BAD_REQUEST, "decision is required: approved or rejected");
            }
            return switch (body.decision()) {
                case "approved" -> {
                    if (blank(body.nodeId()) || blank(body.actionDigest())) {
                        yield error(HttpStatus.BAD_REQUEST, "an approval needs node_id and the action_digest "
                                + "of the request the reviewer was shown");
                    }
                    yield forward(() -> client.approve(executionId, body.nodeId(), body.actionDigest(), body.comment()));
                }
                case "rejected" -> forward(() -> client.reject(executionId, body.nodeId(), body.comment()));
                default -> error(HttpStatus.BAD_REQUEST, "unknown decision: " + body.decision());
            };
        });
    }

    /** The checks every request passes before anything reaches the engine. */
    private ResponseEntity<String> asApprover(Principal principal, String executionId,
                                              Function<JamjetEngineClient, ResponseEntity<String>> call) {
        if (principal == null) {
            return error(HttpStatus.UNAUTHORIZED, "sign in first: this endpoint needs an authenticated user");
        }
        if (executionId == null || !EXECUTION_ID.matcher(executionId).matches()) {
            return error(HttpStatus.BAD_REQUEST, "not an execution id");
        }
        Optional<String> token;
        try {
            token = tokens.engineTokenFor(principal);
        } catch (RuntimeException e) {
            log.warn("JamJet approvals: token lookup failed for {}", principal.getName(), e);
            token = Optional.empty();
        }
        if (token.isEmpty()) {
            return error(HttpStatus.FORBIDDEN, "no JamJet engine token is configured for this user");
        }
        try {
            if (!engine.requiresAuthentication()) {
                log.warn("JamJet approvals: the engine runs without authentication; refusing to relay decisions");
                return error(HttpStatus.SERVICE_UNAVAILABLE,
                        "the JamJet engine runs without authentication (dev mode); decisions are not relayed to it");
            }
        } catch (JamjetHttpException e) {
            log.warn("JamJet approvals: could not confirm the engine requires authentication: {}", e.getMessage());
            return error(HttpStatus.SERVICE_UNAVAILABLE, "could not confirm the JamJet engine requires authentication");
        }
        return call.apply(engine.withBearerToken(token.get()));
    }

    private static ResponseEntity<String> forward(java.util.function.Supplier<Object> call) {
        Object result;
        try {
            result = call.get();
        } catch (JamjetHttpException e) {
            if (e.statusCode() > 0) {
                return ResponseEntity.status(e.statusCode()).contentType(MediaType.APPLICATION_JSON)
                        .body(e.body() == null ? "{}" : e.body());
            }
            log.warn("JamJet engine unreachable: {}", e.getMessage());
            return error(HttpStatus.BAD_GATEWAY, "the JamJet engine could not be reached");
        }
        return json(HttpStatus.OK, result);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseEntity<String> error(HttpStatus status, String message) {
        return json(status, Map.of("error", message));
    }

    private static ResponseEntity<String> json(HttpStatus status, Object body) {
        try {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(JamjetJson.shared().writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write the response", e);
        }
    }
}
