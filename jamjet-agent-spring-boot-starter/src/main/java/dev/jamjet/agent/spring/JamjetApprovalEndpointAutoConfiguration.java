package dev.jamjet.agent.spring;

import dev.jamjet.agent.client.JamjetEngineClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link JamjetApprovalController} when all of these hold: the app is a servlet web
 * app with Spring MVC, a {@link JamjetEngineClient} exists, and
 * {@code jamjet.agent.approvals.endpoint.enabled=true}. Off by default: an endpoint that
 * decides approvals should exist only where someone chose it.
 *
 * <p>The class-level conditions use class names, so this class is skipped (not loaded) in an
 * app without Spring MVC.</p>
 */
@AutoConfiguration(after = JamjetAgentAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnProperty(prefix = "jamjet.agent.approvals.endpoint", name = "enabled", havingValue = "true")
public class JamjetApprovalEndpointAutoConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JamjetApprovalEndpointAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ApproverTokenResolver jamjetApproverTokenResolver(JamjetAgentProperties properties) {
        var approvers = properties.getApprovals().getEndpoint().getApprovers();
        java.util.Map<String, String> tokens = new java.util.LinkedHashMap<>();
        for (int i = 0; i < approvers.size(); i++) {
            var a = approvers.get(i);
            if (a == null || a.getUser() == null || a.getUser().isBlank()
                    || a.getToken() == null || a.getToken().isBlank()) {
                throw new IllegalStateException(
                        "jamjet.agent.approvals.endpoint.approvers[" + i + "] needs both user and token");
            }
            if (tokens.putIfAbsent(a.getUser(), a.getToken()) != null) {
                throw new IllegalStateException(
                        "jamjet.agent.approvals.endpoint.approvers lists " + a.getUser() + " twice");
            }
        }
        if (tokens.isEmpty()) {
            log.warn("JamJet approvals endpoint is enabled but no approvers are configured "
                    + "(jamjet.agent.approvals.endpoint.approvers[n].user / .token); every request will be refused");
        }
        return ApproverTokenResolver.fromMap(tokens);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JamjetEngineClient.class)
    public JamjetApprovalController jamjetApprovalController(JamjetEngineClient engine, ApproverTokenResolver tokens) {
        return new JamjetApprovalController(engine, tokens);
    }
}
