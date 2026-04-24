package dev.jamjet.runtime.plugins;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of loaded {@link PluginDescriptor} instances, keyed by plugin name.
 */
public final class PluginRegistry {

    private final ConcurrentHashMap<String, PluginDescriptor> plugins = new ConcurrentHashMap<>();

    /**
     * Registers (or replaces) the given descriptor, keyed by {@link PluginDescriptor#name()}.
     *
     * @param descriptor the descriptor to register; must not be null
     */
    public void register(PluginDescriptor descriptor) {
        java.util.Objects.requireNonNull(descriptor, "descriptor must not be null");
        plugins.put(descriptor.name(), descriptor);
    }

    /**
     * Returns the descriptor for the given plugin name, if present.
     *
     * @param name the plugin name
     * @return the descriptor, or empty if no plugin with that name is registered
     */
    public Optional<PluginDescriptor> get(String name) {
        return Optional.ofNullable(plugins.get(name));
    }

    /**
     * Removes the descriptor for the given plugin name. No-op if not present.
     *
     * @param name the plugin name to remove
     */
    public void unregister(String name) {
        plugins.remove(name);
    }

    /**
     * Returns an immutable snapshot of all currently registered descriptors.
     */
    public List<PluginDescriptor> listAll() {
        return List.copyOf(plugins.values());
    }
}
