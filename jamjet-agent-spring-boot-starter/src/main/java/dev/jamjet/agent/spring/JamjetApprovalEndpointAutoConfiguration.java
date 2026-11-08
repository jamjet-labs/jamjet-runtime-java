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
        var tokens = properties.getApprovals().getEndpoint().getApproverTokens();
        if (tokens.isEmpty()) {
            log.warn("JamJet approvals endpoint is enabled but no approver tokens are configured "
                    + "(jamjet.agent.approvals.endpoint.approver-tokens.<user>); every request will be refused");
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
