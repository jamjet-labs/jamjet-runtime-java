package dev.jamjet.runtime.plugins;

import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads {@link JamjetPlugin} implementations from JAR files in a directory.
 *
 * <p>Each JAR gets its own isolated {@link URLClassLoader}, preventing class-level
 * conflicts between plugins. Plugins are discovered inside each JAR via
 * {@link ServiceLoader} using the {@link JamjetPlugin} service interface.</p>
 *
 * <p>Thread-safety: {@link #scanAndLoad()} and {@link #unload(String)} are
 * synchronized on {@code this}. Concurrent reads via {@link PluginRegistry} are
 * safe because {@link PluginRegistry} uses a {@link ConcurrentHashMap}.</p>
 */
public final class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final Path pluginDirectory;
    private final NodeExecutorRegistry executorRegistry;
    private final PluginRegistry pluginRegistry;

    /** Tracks open ClassLoaders by plugin name so they can be closed on unload. */
    private final Map<String, URLClassLoader> classLoaders = new ConcurrentHashMap<>();

    /** Tracks loaded plugin instances by name for lifecycle callbacks. */
    private final Map<String, JamjetPlugin> loadedPlugins = new ConcurrentHashMap<>();

    /**
     * Creates a PluginLoader.
     *
     * @param pluginDirectory  directory to scan for *.jar files; created on first scan if absent
     * @param executorRegistry runtime executor registry passed to plugins via {@link PluginContext}
     * @param pluginRegistry   registry updated with {@link PluginDescriptor} entries after each load/unload
     */
    public PluginLoader(Path pluginDirectory,
                        NodeExecutorRegistry executorRegistry,
                        PluginRegistry pluginRegistry) {
        this.pluginDirectory = Objects.requireNonNull(pluginDirectory, "pluginDirectory must not be null");
        this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry must not be null");
        this.pluginRegistry = Objects.requireNonNull(pluginRegistry, "pluginRegistry must not be null");
    }

    /**
     * Scans {@link #pluginDirectory} for {@code *.jar} files and loads each one.
     * Creates the directory if it does not yet exist.
     *
     * <p>JARs that are already loaded (same plugin name) are skipped. Call
     * {@link #reload(String, Path)} to force a reload.</p>
     */
    public synchronized void scanAndLoad() throws IOException {
        ensureDirectoryExists();

        List<Path> jars;
        try (var stream = Files.list(pluginDirectory)) {
            jars = stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .toList();
        }

        if (jars.isEmpty()) {
            log.debug("No JAR files found in plugin directory: {}", pluginDirectory);
            return;
        }

        for (Path jar : jars) {
            try {
                loadJar(jar);
            } catch (Exception e) {
                log.error("Failed to load plugin JAR: {}", jar, e);
            }
        }
    }

    /**
     * Loads all {@link JamjetPlugin} services found in the given JAR.
     * Each JAR gets an isolated {@link URLClassLoader} as its parent is the
     * <em>platform</em> class loader (not the application class loader), which
     * prevents the plugin from accidentally accessing application internals beyond
     * the published API surface.
     *
     * @param jarPath path to the JAR file to load
     * @throws IOException if the JAR cannot be opened
     */
    public synchronized void loadJar(Path jarPath) throws IOException {
        Objects.requireNonNull(jarPath, "jarPath must not be null");
        log.info("Loading plugin JAR: {}", jarPath);

        URL jarUrl = jarPath.toUri().toURL();
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarUrl},
                ClassLoader.getPlatformClassLoader()
        );

        ServiceLoader<JamjetPlugin> serviceLoader = ServiceLoader.load(JamjetPlugin.class, classLoader);
        List<JamjetPlugin> discovered = new ArrayList<>();
        serviceLoader.forEach(discovered::add);

        if (discovered.isEmpty()) {
            log.warn("No JamjetPlugin services found in JAR: {}. Closing loader.", jarPath);
            classLoader.close();
            return;
        }

        PluginContext context = new PluginContext(executorRegistry);

        for (JamjetPlugin plugin : discovered) {
            String name = plugin.name();

            // Close any previous ClassLoader for this plugin name (hot-replace scenario)
            URLClassLoader previous = classLoaders.put(name, classLoader);
            if (previous != null && previous != classLoader) {
                try {
                    previous.close();
                } catch (IOException e) {
                    log.warn("Could not close previous ClassLoader for plugin '{}': {}", name, e.getMessage());
                }
            }

            try {
                plugin.onLoad(context);
                loadedPlugins.put(name, plugin);
                pluginRegistry.register(new PluginDescriptor(name, plugin.version(), PluginDescriptor.State.LOADED));
                log.info("Plugin loaded: {} v{}", name, plugin.version());
            } catch (Exception e) {
                loadedPlugins.remove(name);
                pluginRegistry.register(new PluginDescriptor(name, plugin.version(), PluginDescriptor.State.FAILED));
                log.error("Plugin '{}' threw an exception during onLoad — marked FAILED", name, e);
            }
        }
    }

    /**
     * Unloads the named plugin: calls {@link JamjetPlugin#onUnload()}, removes it from
     * the registry, and closes the isolated {@link URLClassLoader}.
     *
     * @param name the plugin name to unload
     */
    public synchronized void unload(String name) {
        Objects.requireNonNull(name, "name must not be null");

        JamjetPlugin plugin = loadedPlugins.remove(name);
        if (plugin != null) {
            try {
                plugin.onUnload();
            } catch (Exception e) {
                log.warn("Plugin '{}' threw an exception during onUnload — continuing", name, e);
            }
            pluginRegistry.register(new PluginDescriptor(name, plugin.version(), PluginDescriptor.State.UNLOADED));
        } else {
            log.debug("unload called for unknown or already-unloaded plugin: {}", name);
        }

        URLClassLoader cl = classLoaders.remove(name);
        if (cl != null) {
            try {
                cl.close();
            } catch (IOException e) {
                log.warn("Could not close ClassLoader for plugin '{}': {}", name, e.getMessage());
            }
        }
    }

    /**
     * Convenience method: unloads any existing instance of the named plugin then
     * loads the JAR at the given path.
     *
     * @param name    the plugin name to replace
     * @param jarPath the path to the new JAR
     * @throws IOException if the JAR cannot be opened
     */
    public synchronized void reload(String name, Path jarPath) throws IOException {
        unload(name);
        loadJar(jarPath);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void ensureDirectoryExists() throws IOException {
        if (!Files.exists(pluginDirectory)) {
            log.info("Plugin directory does not exist, creating: {}", pluginDirectory);
            Files.createDirectories(pluginDirectory);
        }
    }
}
