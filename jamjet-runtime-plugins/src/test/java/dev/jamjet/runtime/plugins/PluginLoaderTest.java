package dev.jamjet.runtime.plugins;

import dev.jamjet.runtime.core.executor.NodeExecutorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PluginLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void scanEmptyDirectoryLoadsNothing() throws IOException {
        NodeExecutorRegistry executorRegistry = new NodeExecutorRegistry();
        PluginRegistry pluginRegistry = new PluginRegistry();
        PluginLoader loader = new PluginLoader(tempDir, executorRegistry, pluginRegistry);

        loader.scanAndLoad();

        assertThat(pluginRegistry.listAll()).isEmpty();
    }

    @Test
    void scanIgnoresNonJarFiles() throws IOException {
        // Place non-JAR files in the directory
        Files.writeString(tempDir.resolve("readme.txt"), "not a jar");
        Files.writeString(tempDir.resolve("config.xml"), "<config/>");
        Files.createDirectory(tempDir.resolve("subdir"));

        NodeExecutorRegistry executorRegistry = new NodeExecutorRegistry();
        PluginRegistry pluginRegistry = new PluginRegistry();
        PluginLoader loader = new PluginLoader(tempDir, executorRegistry, pluginRegistry);

        loader.scanAndLoad();

        assertThat(pluginRegistry.listAll()).isEmpty();
    }

    @Test
    void pluginDirectoryCreatedIfMissing(@TempDir Path baseDir) throws IOException {
        Path missingDir = baseDir.resolve("plugins");
        assertThat(missingDir).doesNotExist();

        NodeExecutorRegistry executorRegistry = new NodeExecutorRegistry();
        PluginRegistry pluginRegistry = new PluginRegistry();
        PluginLoader loader = new PluginLoader(missingDir, executorRegistry, pluginRegistry);

        loader.scanAndLoad();

        assertThat(missingDir).exists().isDirectory();
        assertThat(pluginRegistry.listAll()).isEmpty();
    }
}
