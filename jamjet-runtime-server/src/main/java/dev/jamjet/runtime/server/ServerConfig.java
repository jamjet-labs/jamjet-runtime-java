package dev.jamjet.runtime.server;

/**
 * Configuration for the JamJet REST server.
 */
public record ServerConfig(
        int port,
        String storageBackend,
        String databaseUrl
) {

    public static ServerConfig defaults() {
        return new ServerConfig(7070, "in-memory", null);
    }

    public static ServerConfig fromEnv() {
        String portStr = System.getenv("JAMJET_PORT");
        int port = portStr != null ? Integer.parseInt(portStr) : 7070;

        String storageBackend = System.getenv("JAMJET_STORAGE_BACKEND");
        if (storageBackend == null) {
            storageBackend = "in-memory";
        }

        String databaseUrl = System.getenv("JAMJET_DATABASE_URL");

        return new ServerConfig(port, storageBackend, databaseUrl);
    }
}
