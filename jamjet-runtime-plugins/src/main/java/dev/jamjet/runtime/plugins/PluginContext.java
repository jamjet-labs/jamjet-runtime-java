package dev.jamjet.runtime.plugins;

import dev.jamjet.runtime.core.executor.NodeExecutor;
import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;

import java.util.Objects;

/**
 * Context provided to a {@link JamjetPlugin} during {@link JamjetPlugin#onLoad(PluginContext)}.
 *
 * <p>Gives plugins a controlled surface to interact with the runtime — currently
 * limited to registering custom {@link NodeExecutor} implementations.</p>
 */
public final class PluginContext {

    private final NodeExecutorRegistry executorRegistry;

    /**
     * Creates a new PluginContext backed by the given registry.
     *
     * @param executorRegistry the runtime's node executor registry; must not be null
     */
    public PluginContext(NodeExecutorRegistry executorRegistry) {
        this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry must not be null");
    }

    /**
     * Registers a custom {@link NodeExecutor} for the given kind tag.
     * Replaces any previously registered executor for the same tag.
     *
     * @param kindTag  the node kind tag this executor handles (e.g. "my-company.fetch")
     * @param executor the executor implementation; must not be null
     */
    public void registerNodeExecutor(String kindTag, NodeExecutor executor) {
        Objects.requireNonNull(kindTag, "kindTag must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        executorRegistry.register(kindTag, executor);
    }
}
