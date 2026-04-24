package dev.jamjet.runtime.core.ir;

import java.util.List;

public record McpServerConfig(
        McpTransport transport,
        String command,
        List<String> args,
        String url,
        AuthConfig auth
) {
    public McpServerConfig {
        args = args == null ? List.of() : List.copyOf(args);
    }
}
