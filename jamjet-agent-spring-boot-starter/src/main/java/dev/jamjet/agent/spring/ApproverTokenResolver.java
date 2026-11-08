package dev.jamjet.agent.spring;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

/**
 * The engine token the approval endpoint acts with for an authenticated person. The engine
 * records the approver from this token and refuses a run's own initiator, so give each
 * person their own token, distinct from the application's {@code jamjet.agent.bearer-token}.
 *
 * <p>The default reads {@code jamjet.agent.approvals.endpoint.approvers[n].user} and
 * {@code .token} pairs; startup fails on a pair missing either value or on a user listed
 * twice. Define a bean of this type to look tokens up elsewhere, such as a vault. An empty
 * result refuses the request (403).</p>
 */
@FunctionalInterface
public interface ApproverTokenResolver {

    Optional<String> engineTokenFor(Principal principal);

    /** Looks the principal's name up in {@code tokens}; blank tokens count as absent. */
    static ApproverTokenResolver fromMap(Map<String, String> tokens) {
        Map<String, String> copy = Map.copyOf(tokens);
        return principal -> Optional.ofNullable(copy.get(principal.getName())).filter(t -> !t.isBlank());
    }
}
