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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Lets a person decide a parked approval through the application:
 * {@code GET {path}/executions/{id}/approvals} and {@code POST {path}/executions/{id}/approve}.
 * Bodies and responses have the engine's shape (snake_case).
 *
 * <p>Every call is forwarded with the <em>caller's</em> {@code Authorization: Bearer} token,
 * never the application's. The engine records the approver from the token and refuses a
 * run's own initiator as its approver, so deciding with the application's token would either
 * be refused or record the wrong person. A request with no bearer token gets 401 and the
 * engine is not called.</p>
 *
 * <p>An {@code approved} decision must carry the {@code action_digest} the reviewer was
 * shown (from the pending request's {@code context}); the engine refuses a stale one with 409.
 * The endpoint refuses an approval without one (400) instead of letting it through.</p>
 */
@RestController
@RequestMapping("${jamjet.agent.approvals.endpoint.path:/jamjet}")
public class JamjetApprovalController {

    private static final Logger log = LoggerFactory.getLogger(JamjetApprovalController.class);

    private final JamjetEngineClient engine;

    public JamjetApprovalController(JamjetEngineClient engine) {
        this.engine = engine;
    }

    /** The decision body, as the engine takes it. */
    public record DecisionRequest(
            @JsonProperty("decision") String decision,
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("action_digest") String actionDigest,
            @JsonProperty("comment") String comment) {
    }

    @GetMapping("/executions/{executionId}/approvals")
    public ResponseEntity<String> list(
            @PathVariable("executionId") String executionId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = bearer(authorization);
        if (token == null) {
            return error(HttpStatus.UNAUTHORIZED, "send your own Authorization: Bearer <token>");
        }
        return forward(() -> engine.withBearerToken(token).listApprovals(executionId));
    }

    @PostMapping("/executions/{executionId}/approve")
    public ResponseEntity<String> decide(
            @PathVariable("executionId") String executionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) DecisionRequest body) {
        String token = bearer(authorization);
        if (token == null) {
            return error(HttpStatus.UNAUTHORIZED, "send your own Authorization: Bearer <token>");
        }
        if (body == null || blank(body.decision())) {
            return error(HttpStatus.BAD_REQUEST, "decision is required: approved or rejected");
        }
        JamjetEngineClient asCaller = engine.withBearerToken(token);
        return switch (body.decision()) {
            case "approved" -> {
                if (blank(body.nodeId()) || blank(body.actionDigest())) {
                    yield error(HttpStatus.BAD_REQUEST, "an approval needs node_id and the action_digest "
                            + "of the request the reviewer was shown");
                }
                yield forward(() -> asCaller.approve(executionId, body.nodeId(), body.actionDigest(), body.comment()));
            }
            case "rejected" -> forward(() -> asCaller.reject(executionId, body.nodeId(), body.comment()));
            default -> error(HttpStatus.BAD_REQUEST, "unknown decision: " + body.decision());
        };
    }

    private static ResponseEntity<String> forward(Supplier<Object> call) {
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

    private static String bearer(String authorization) {
        if (authorization == null || authorization.length() < 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
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
