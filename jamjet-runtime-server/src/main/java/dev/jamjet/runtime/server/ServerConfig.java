package dev.jamjet.runtime.server;

/**
 * Configuration for the JamJet REST server.
 *
 * @param port           TCP port; 0 picks a free one
 * @param storageBackend storage backend name ({@code in-memory} is the only one)
 * @param databaseUrl    reserved for a JDBC backend, unused
 * @param host           address to bind; {@code 127.0.0.1} unless set
 * @param apiToken       when set, every request except {@code GET /health} needs
 *                       {@code Authorization: Bearer <apiToken>}
 */
public record ServerConfig(
        int port,
        String storageBackend,
        String databaseUrl,
        String host,
        String apiToken
) {

    /** Loopback only: nothing off this machine can reach the server unless {@code host} says so. */
    public static final String DEFAULT_HOST = "127.0.0.1";

    public ServerConfig {
        if (host == null || host.isBlank()) {
            host = DEFAULT_HOST;
        }
        if (apiToken != null && apiToken.isBlank()) {
            apiToken = null;
        }
    }

    /** Loopback bind, no token. */
    public ServerConfig(int port, String storageBackend, String databaseUrl) {
        this(port, storageBackend, databaseUrl, DEFAULT_HOST, null);
    }

    public static ServerConfig defaults() {
        return new ServerConfig(7070, "in-memory", null);
    }

    /**
     * Reads {@code JAMJET_PORT}, {@code JAMJET_STORAGE_BACKEND}, {@code JAMJET_DATABASE_URL},
     * {@code JAMJET_BIND} (the address to bind, as in the Rust runtime) and
     * {@code JAMJET_API_TOKEN}.
     */
    public static ServerConfig fromEnv() {
        String portStr = System.getenv("JAMJET_PORT");
        int port = portStr != null ? Integer.parseInt(portStr) : 7070;

        String storageBackend = System.getenv("JAMJET_STORAGE_BACKEND");
        if (storageBackend == null) {
            storageBackend = "in-memory";
        }

        String databaseUrl = System.getenv("JAMJET_DATABASE_URL");

        return new ServerConfig(port, storageBackend, databaseUrl,
                System.getenv("JAMJET_BIND"), System.getenv("JAMJET_API_TOKEN"));
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "ServerConfig[port=" + port + ", storageBackend=" + storageBackend
                + ", databaseUrl=" + databaseUrl + ", host=" + host
                + ", apiToken=" + (apiToken == null ? "unset" : "set") + "]";
    }
}
