package dev.jamjet.runtime.plugins;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PluginRegistryTest {

    @Test
    void registerAndGet() {
        PluginRegistry registry = new PluginRegistry();
        PluginDescriptor descriptor = new PluginDescriptor("my-plugin", "1.0.0", PluginDescriptor.State.LOADED);

        registry.register(descriptor);

        Optional<PluginDescriptor> result = registry.get("my-plugin");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("my-plugin");
        assertThat(result.get().version()).isEqualTo("1.0.0");
        assertThat(result.get().state()).isEqualTo(PluginDescriptor.State.LOADED);
    }

    @Test
    void getMissingReturnsEmpty() {
        PluginRegistry registry = new PluginRegistry();

        Optional<PluginDescriptor> result = registry.get("nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    void listAllReturnsAll() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(new PluginDescriptor("plugin-a", "1.0.0", PluginDescriptor.State.LOADED));
        registry.register(new PluginDescriptor("plugin-b", "2.0.0", PluginDescriptor.State.FAILED));

        List<PluginDescriptor> all = registry.listAll();

        assertThat(all).hasSize(2);
        assertThat(all).extracting(PluginDescriptor::name)
                .containsExactlyInAnyOrder("plugin-a", "plugin-b");
    }

    @Test
    void unregisterRemoves() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(new PluginDescriptor("to-remove", "1.0.0", PluginDescriptor.State.LOADED));

        registry.unregister("to-remove");

        assertThat(registry.get("to-remove")).isEmpty();
        assertThat(registry.listAll()).isEmpty();
    }
}
