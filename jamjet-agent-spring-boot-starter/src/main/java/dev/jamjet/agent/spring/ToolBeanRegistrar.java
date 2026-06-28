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
import org.springframework.util.ClassUtils;

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
 * <h2>Lazy and not-yet-created beans</h2>
 * Discovery walks bean <em>definitions</em> ({@code getBeanDefinitionNames}) and resolves each
 * bean's type with {@link ConfigurableListableBeanFactory#getType(String)} — which does NOT
 * force instantiation — rather than scanning only the singletons already created. A
 * {@code @Lazy @Tool} bean is therefore still found and registered (its bean is realized on
 * demand via {@code getBean} only once a {@code @Tool} method is detected on its user class),
 * instead of silently vanishing the way a missed proxy would. Beans with no {@code @Tool}
 * method are never instantiated, and only singleton-scoped beans are registered (the worker
 * holds one instance by reference, so a prototype tool-holder has no well-defined identity).
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
            // Resolve the bean's type from its definition WITHOUT instantiating it. This is what
            // lets a @Lazy @Tool bean — not yet created when this callback runs — still be
            // discovered, instead of being silently dropped the way a missed proxy would be.
            Class<?> type = beanFactory.getType(name);
            if (type == null) {
                // Some infrastructure beans report no resolvable type — nothing to scan.
                continue;
            }
            // Detect @Tool on the user class register() will ultimately scan: strip any CGLIB
            // subclass at the type level (the type-only analogue of the raw-target unwrap below),
            // so detection and post-unwrap registration never disagree and we never read a
            // proxy's bare, annotation-less methods.
            if (!hasToolMethod(ClassUtils.getUserClass(type))) {
                // No @Tool method — skip WITHOUT getBean so an unrelated lazy bean is never
                // eagerly instantiated as a side effect of scanning.
                continue;
            }
            if (!beanFactory.isSingleton(name)) {
                // Register only singleton-scoped holders (the worker keeps one instance by
                // reference); this preserves the prior singleton-only semantics while fixing
                // the lazy-singleton case. A prototype tool-holder has no stable identity.
                continue;
            }
            // Realize the bean (instantiating a lazy singleton if needed; a no-op for an already
            // created one), then unwrap any AOP proxy to the raw target whose user class carries
            // the @Tool annotations register() scans.
            Object holder = unwrapAopTarget(beanFactory.getBean(name));
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
