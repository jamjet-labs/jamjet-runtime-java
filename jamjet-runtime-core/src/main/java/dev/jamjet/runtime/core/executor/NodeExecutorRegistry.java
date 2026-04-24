package dev.jamjet.runtime.core.executor;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry mapping node kind tags to {@link NodeExecutor} instances.
 */
public class NodeExecutorRegistry {

    private final ConcurrentHashMap<String, NodeExecutor> executors = new ConcurrentHashMap<>();

    /**
     * Registers an executor for the given kind tag, replacing any previous mapping.
     *
     * @param kindTag  the node kind tag (e.g. "model", "tool")
     * @param executor the executor to handle nodes of this kind
     */
    public void register(String kindTag, NodeExecutor executor) {
        executors.put(kindTag, executor);
    }

    /**
     * Looks up the executor registered for the given kind tag.
     *
     * @param kindTag the node kind tag
     * @return the executor, or empty if none is registered
     */
    public Optional<NodeExecutor> get(String kindTag) {
        return Optional.ofNullable(executors.get(kindTag));
    }

    /**
     * Returns a snapshot of all currently registered kind tags.
     */
    public Set<String> registeredKinds() {
        return Set.copyOf(executors.keySet());
    }
}
