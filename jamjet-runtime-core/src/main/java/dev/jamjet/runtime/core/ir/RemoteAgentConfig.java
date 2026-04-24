package dev.jamjet.runtime.core.ir;

public record RemoteAgentConfig(
        String url,
        String agentCardPath,
        AuthConfig auth
) {}
