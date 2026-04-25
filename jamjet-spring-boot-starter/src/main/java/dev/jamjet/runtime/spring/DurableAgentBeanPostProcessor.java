package dev.jamjet.runtime.spring;

import dev.jamjet.runtime.instrument.annotations.DurableAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Spring {@link BeanPostProcessor} that detects beans annotated with {@link DurableAgent}
 * and tracks them for JamJet runtime instrumentation.
 */
public class DurableAgentBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DurableAgentBeanPostProcessor.class);

    private final List<String> discoveredAgents = new CopyOnWriteArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> beanClass = bean.getClass();
        // Also check the superclass hierarchy in case of Spring proxies wrapping the target
        if (hasAnnotation(beanClass)) {
            DurableAgent annotation = findAnnotation(beanClass);
            String agentName = (annotation != null && !annotation.value().isBlank())
                    ? annotation.value()
                    : beanClass.getSimpleName();
            log.info("JamJet: discovered @DurableAgent bean '{}' (class: {})", agentName, beanClass.getName());
            discoveredAgents.add(agentName);
        }
        return bean;
    }

    /**
     * Returns an unmodifiable snapshot of all discovered agent names.
     */
    public List<String> getDiscoveredAgents() {
        return Collections.unmodifiableList(discoveredAgents);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static boolean hasAnnotation(Class<?> clazz) {
        return findAnnotation(clazz) != null;
    }

    private static DurableAgent findAnnotation(Class<?> clazz) {
        if (clazz == null || clazz == Object.class) {
            return null;
        }
        DurableAgent ann = clazz.getAnnotation(DurableAgent.class);
        if (ann != null) {
            return ann;
        }
        // Walk up the hierarchy to handle CGLIB/JDK proxies
        DurableAgent fromSuper = findAnnotation(clazz.getSuperclass());
        if (fromSuper != null) {
            return fromSuper;
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            DurableAgent fromIface = findAnnotation(iface);
            if (fromIface != null) {
                return fromIface;
            }
        }
        return null;
    }
}
