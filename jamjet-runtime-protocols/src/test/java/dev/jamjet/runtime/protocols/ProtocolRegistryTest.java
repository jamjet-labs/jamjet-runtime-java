package dev.jamjet.runtime.protocols;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolRegistryTest {

    private static ProtocolAdapter stubAdapter(String protocolName) {
        return new ProtocolAdapter() {
            @Override public String protocol() { return protocolName; }
            @Override public void start() {}
            @Override public void stop() {}
        };
    }

    @Test
    void register_and_lookup() {
        ProtocolRegistry registry = new ProtocolRegistry();
        ProtocolAdapter adapter = stubAdapter("mcp");

        registry.register("mcp", adapter);

        Optional<ProtocolAdapter> result = registry.get("mcp");
        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(adapter);
    }

    @Test
    void lookup_missing_returns_empty() {
        ProtocolRegistry registry = new ProtocolRegistry();

        Optional<ProtocolAdapter> result = registry.get("a2a");

        assertThat(result).isEmpty();
    }

    @Test
    void registeredProtocols_returns_set() {
        ProtocolRegistry registry = new ProtocolRegistry();
        registry.register("mcp", stubAdapter("mcp"));
        registry.register("a2a", stubAdapter("a2a"));

        assertThat(registry.registeredProtocols()).containsExactlyInAnyOrder("mcp", "a2a");
    }

    @Test
    void register_replaces_existing() {
        ProtocolRegistry registry = new ProtocolRegistry();
        ProtocolAdapter first = stubAdapter("mcp");
        ProtocolAdapter second = stubAdapter("mcp");

        registry.register("mcp", first);
        registry.register("mcp", second);

        assertThat(registry.get("mcp")).contains(second);
        assertThat(registry.registeredProtocols()).containsExactly("mcp");
    }
}
