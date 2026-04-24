package dev.jamjet.runtime.protocols.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link McpClientAdapter}.
 *
 * <p>These tests do NOT connect to a real MCP server — they verify the contract
 * and compilation only.  Integration tests that require a running stdio process
 * belong in a separate test profile.</p>
 */
class McpClientAdapterTest {

    @Test
    void protocol_returns_mcp() {
        McpClientAdapter adapter = McpClientAdapter.ofStdio("echo", List.of("hello"));
        assertThat(adapter.protocol()).isEqualTo("mcp");
    }

    @Test
    void ofStdio_factory_creates_adapter() {
        // Verifies the factory compiles and produces a non-null adapter
        McpClientAdapter adapter = McpClientAdapter.ofStdio("node", List.of("server.js", "--port", "3000"));
        assertThat(adapter).isNotNull();
        assertThat(adapter.protocol()).isEqualTo("mcp");
    }

    @Test
    void callTool_before_start_throws_IllegalStateException() {
        McpClientAdapter adapter = McpClientAdapter.ofStdio("echo", List.of());

        assertThatThrownBy(() -> adapter.callTool("some_tool", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start()");
    }

    @Test
    void listTools_before_start_throws_IllegalStateException() {
        McpClientAdapter adapter = McpClientAdapter.ofStdio("echo", List.of());

        assertThatThrownBy(() -> adapter.listTools())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start()");
    }

    @Test
    void stop_on_unstarted_adapter_is_idempotent() {
        McpClientAdapter adapter = McpClientAdapter.ofStdio("echo", List.of());
        // Should not throw
        adapter.stop();
        adapter.stop();
    }
}
