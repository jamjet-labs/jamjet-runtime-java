package dev.jamjet.runtime.plugins;

/**
 * Immutable snapshot of a loaded plugin's metadata and lifecycle state.
 *
 * @param name    the unique plugin name
 * @param version the plugin version string
 * @param state   current lifecycle state of the plugin
 */
public record PluginDescriptor(String name, String version, State state) {

    /**
     * Lifecycle states a plugin can be in.
     */
    public enum State {
        /** Plugin was loaded and {@link JamjetPlugin#onLoad(PluginContext)} completed successfully. */
        LOADED,
        /** Plugin was explicitly unloaded via {@link PluginLoader#unload(String)}. */
        UNLOADED,
        /** Plugin loading failed (e.g. bad JAR, missing service declaration, exception in onLoad). */
        FAILED
    }

    public PluginDescriptor {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(version, "version must not be null");
        java.util.Objects.requireNonNull(state, "state must not be null");
    }
}
