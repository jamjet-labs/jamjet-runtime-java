package dev.jamjet.agent.spring;

import dev.jamjet.agent.Tool;
import dev.jamjet.agent.tools.RegisteredTool;
import dev.jamjet.agent.tools.ToolRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-2 tests for {@link ToolBeanRegistrar}: {@code @Tool}-annotated Spring beans — both
 * plain and CGLIB-proxied — are scanned into the auto-configured {@link ToolRegistry},
 * and a duplicate tool name across beans fails context startup cleanly.
 */
class ToolScanningTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetAgentAutoConfiguration.class))
            // Testing tool scanning, not the background drain.
            .withPropertyValues("jamjet.agent.worker.enabled=false");

    @Test
    void scansPlainAndCglibProxiedToolBeansIntoTheRegistry() {
        runner.withUserConfiguration(ToolBeansConfig.class).run(ctx -> {
            ToolRegistry registry = ctx.getBean(ToolRegistry.class);
            assertThat(registry.byName("plain_echo")).isNotNull();
            assertThat(registry.byName("proxied_add")).isNotNull();
            assertThat(registry.tools())
                    .extracting(RegisteredTool::name)
                    .contains("plain_echo", "proxied_add");
        });
    }

    @Test
    void theProxiedToolBeanIsActuallyACglibProxy() {
        // Guard against a vacuous test: prove the bean under scan really is a CGLIB proxy,
        // so the registrar's raw-target unwrap is genuinely exercised.
        runner.withUserConfiguration(ToolBeansConfig.class).run(ctx -> {
            Object proxied = ctx.getBean("proxiedTools");
            assertThat(AopUtils.isCglibProxy(proxied)).isTrue();
        });
    }

    @Test
    void duplicateToolNamesAcrossBeansFailContextStartup() {
        runner.withUserConfiguration(DuplicateToolsConfig.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            // The ToolRegistry duplicate-name guard throws from afterSingletonsInstantiated,
            // which propagates as the context startup failure (unwrapped).
            assertThat(ctx.getStartupFailure())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate @Tool name 'dup'");
        });
    }

    // -- test fixtures ----------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class ToolBeansConfig {

        @Bean
        PlainTools plainTools() {
            return new PlainTools();
        }

        @Bean
        ProxiedTools proxiedTools() {
            // Wrap the holder in a CGLIB AOP proxy (the @Transactional/@Async shape): the
            // @Tool annotation lives on the target's user class, not the proxy subclass.
            ProxyFactory pf = new ProxyFactory(new ProxiedTools());
            pf.setProxyTargetClass(true);
            pf.addAdvice((MethodInterceptor) org.aopalliance.intercept.MethodInvocation::proceed);
            return (ProxiedTools) pf.getProxy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateToolsConfig {

        @Bean
        DuplicateToolsA duplicateToolsA() {
            return new DuplicateToolsA();
        }

        @Bean
        DuplicateToolsB duplicateToolsB() {
            return new DuplicateToolsB();
        }
    }

    public static class PlainTools {
        @Tool(name = "plain_echo", description = "Echo the input string.")
        public String echo(String value) {
            return value;
        }
    }

    public static class ProxiedTools {
        @Tool(name = "proxied_add", description = "Add two integers.")
        public int add(int a, int b) {
            return a + b;
        }
    }

    public static class DuplicateToolsA {
        @Tool(name = "dup", description = "First tool named dup.")
        public String one() {
            return "a";
        }
    }

    public static class DuplicateToolsB {
        @Tool(name = "dup", description = "Second tool also named dup.")
        public String two() {
            return "b";
        }
    }
}
