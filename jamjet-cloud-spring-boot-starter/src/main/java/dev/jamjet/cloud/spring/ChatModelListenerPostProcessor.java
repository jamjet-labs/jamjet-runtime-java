package dev.jamjet.cloud.spring;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reflectively appends a {@link ChatModelListener} to every
 * {@link ChatLanguageModel} bean at startup. Logs WARN once per provider
 * class when a model doesn't expose a listeners list.
 */
public final class ChatModelListenerPostProcessor implements BeanPostProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ChatModelListenerPostProcessor.class);
    private final ChatModelListener listener;
    private final Set<Class<?>> warned = new HashSet<>();

    public ChatModelListenerPostProcessor(ChatModelListener listener) {
        this.listener = listener;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof ChatLanguageModel)) return bean;
        Field field = findListenersField(bean.getClass());
        if (field == null) {
            warnOnce(bean.getClass());
            return bean;
        }
        try {
            field.setAccessible(true);
            Object current = field.get(bean);
            if (current instanceof List) {
                ((List<ChatModelListener>) current).add(listener);
            } else {
                warnOnce(bean.getClass());
            }
        } catch (Throwable t) {
            LOG.warn("Could not append JamjetChatModelListener to {}: {}",
                    bean.getClass().getName(), t.toString());
        }
        return bean;
    }

    private static Field findListenersField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals("listeners") && List.class.isAssignableFrom(f.getType())) {
                    return f;
                }
            }
        }
        return null;
    }

    private void warnOnce(Class<?> type) {
        if (warned.add(type)) {
            LOG.warn("{} does not expose a listeners list; spans for this model will not be captured. "
                    + "Wire JamjetChatModelListener manually via the model builder.", type.getName());
        }
    }
}
