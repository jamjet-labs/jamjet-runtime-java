package dev.jamjet.agent.spring;

import dev.jamjet.agent.Tool;
import dev.jamjet.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scans the Spring context for {@link Tool @Tool}-annotated beans after all singletons
 * are instantiated and registers them into the auto-configured {@link ToolRegistry} —
 * the Spring-idiomatic way to author tools as ordinary {@code @Component} beans. This is
 * the same registry the {@link dev.jamjet.agent.worker.JavaToolWorker} drains and the one
 * a user's {@code Agent} should be built with ({@code Agent.builder(...).registry(registry)}),
 * so the agent's IR and the worker share one tool set.
 *
 * <h2>CGLIB / proxy awareness</h2>
 * {@link ToolRegistry#register(Object)} scans {@code holder.getClass().getDeclaredMethods()}.
 * For a CGLIB (or JDK) AOP proxy — e.g. a {@code @Transactional} / {@code @Async} tool bean —
 * the {@code @Tool} annotations live on the <em>target's</em> user class, not on the generated
 * proxy subclass, so registering the proxy directly would discover zero tools. This registrar
 * therefore resolves the raw singleton target ({@link AopProxyUtils#getSingletonTarget}) before
 * registering, so a proxied tool-holder still contributes its tools. (Tools are invoked directly
 * on that target, bypassing the proxy's advice — the documented trade-off of authoring tools as
 * proxied beans.)
 *
 * <h2>Determinism + duplicate guard</h2>
 * Beans are scanned in bean-name order so the emitted tool order is stable across runs and the
 * {@link ToolRegistry} duplicate-tool-name guard fails the same way every time: two beans
 * declaring the same {@code @Tool} name surface as a clear context-startup failure (an
 * {@link IllegalArgumentException} from {@code register}) rather than being silently dropped.
 */
public class ToolBeanRegistrar implements SmartInitializingSingleton, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(ToolBeanRegistrar.class);

    private final ToolRegistry registry;
    private ConfigurableListableBeanFactory beanFactory;

    public ToolBeanRegistrar(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof ConfigurableListableBeanFactory clbf) {
            this.beanFactory = clbf;
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (beanFactory == null) {
            return;
        }
        // Bean-name order: stable tool order + a deterministic duplicate-name failure.
        List<String> names = new ArrayList<>(Arrays.asList(beanFactory.getBeanDefinitionNames()));
        names.sort(String::compareTo);

        List<String> scanned = new ArrayList<>();
        for (String name : names) {
            Object singleton = beanFactory.getSingleton(name);
            if (singleton == null) {
                // Not yet created (lazy / non-singleton) — nothing instantiated to scan.
                continue;
            }
            Object holder = unwrapAopTarget(singleton);
            // Detect on exactly the class register() will scan (the raw target's user class
            // after unwrapping), so detection and registration never disagree.
            if (!hasToolMethod(holder.getClass())) {
                continue;
            }
            registry.register(holder);
            scanned.add(name);
        }
        if (!scanned.isEmpty()) {
            log.info("JamJet: registered @Tool method(s) from {} Spring bean(s): {}", scanned.size(), scanned);
        }
    }

    /** Resolve the raw singleton target behind an AOP proxy; pass plain beans through unchanged. */
    private static Object unwrapAopTarget(Object bean) {
        if (AopUtils.isAopProxy(bean)) {
            Object target = AopProxyUtils.getSingletonTarget(bean);
            if (target != null) {
                return target;
            }
        }
        return bean;
    }

    private static boolean hasToolMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                return true;
            }
        }
        return false;
    }
}
