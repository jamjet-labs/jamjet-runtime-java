package dev.jamjet.runtime.plugins;

/**
 * SPI interface for JamJet runtime plugins.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}. Each plugin
 * must declare its implementation class in
 * {@code META-INF/services/dev.jamjet.runtime.plugins.JamjetPlugin}.</p>
 *
 * <p>Implementations must have a public no-arg constructor.</p>
 */
public interface JamjetPlugin {

    /**
     * Returns the unique name of this plugin (e.g. "my-company-tools").
     */
    String name();

    /**
     * Returns the version string of this plugin (e.g. "1.2.0").
     */
    String version();

    /**
     * Called by the runtime after the plugin has been loaded and its class is available.
     * Plugins should use this callback to register their {@link dev.jamjet.runtime.core.executor.NodeExecutor}
     * implementations via the provided {@link PluginContext}.
     *
     * @param context the plugin context providing access to runtime registries
     */
    void onLoad(PluginContext context);

    /**
     * Called by the runtime when the plugin is being unloaded (e.g. during shutdown or hot-reload).
     * Plugins should release any resources acquired in {@link #onLoad(PluginContext)}.
     */
    void onUnload();
}
