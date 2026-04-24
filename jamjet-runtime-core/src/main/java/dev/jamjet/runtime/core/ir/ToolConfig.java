package dev.jamjet.runtime.core.ir;

import java.util.List;

public record ToolConfig(
        ToolKind kind,
        String reference,
        String inputSchema,
        String outputSchema,
        List<String> permissions
) {
    public ToolConfig {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
