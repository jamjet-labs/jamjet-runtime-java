package dev.jamjet.cloud;

/**
 * Immutable SDK configuration. Use {@link #builder()}.
 *
 * <p>Mirrors {@code jamjet.cloud.configure(...)} kwargs in the Python SDK so
 * a polyglot team can configure both side-by-side.
 */
public final class JamjetCloudConfig {

    private final String apiKey;
    private final String apiUrl;
    private final String project;
    private final String agentName;
    private final String environment;
    private final String releaseVersion;
    private final long flushIntervalMs;
    private final int flushSize;
    private final boolean autoPatch;
    private final boolean captureIo;

    private JamjetCloudConfig(Builder b) {
        this.apiKey = b.apiKey;
        this.apiUrl = b.apiUrl;
        this.project = b.project;
        this.agentName = b.agentName;
        this.environment = b.environment;
        this.releaseVersion = b.releaseVersion;
        this.flushIntervalMs = b.flushIntervalMs;
        this.flushSize = b.flushSize;
        this.autoPatch = b.autoPatch;
        this.captureIo = b.captureIo;
    }

    public String apiKey()           { return apiKey; }
    public String apiUrl()           { return apiUrl; }
    public String project()          { return project; }
    public String agentName()        { return agentName; }
    public String environment()      { return environment; }
    public String releaseVersion()   { return releaseVersion; }
    public long flushIntervalMs()    { return flushIntervalMs; }
    public int flushSize()           { return flushSize; }
    public boolean autoPatch()       { return autoPatch; }
    public boolean captureIo()       { return captureIo; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiKey;
        private String apiUrl = "https://api.jamjet.dev";
        private String project = "default";
        private String agentName;
        private String environment;
        private String releaseVersion;
        private long flushIntervalMs = 5_000L;
        private int flushSize = 50;
        private boolean autoPatch = true;
        private boolean captureIo = false;

        public Builder apiKey(String v)          { this.apiKey = v; return this; }
        public Builder apiUrl(String v)          { this.apiUrl = v; return this; }
        public Builder project(String v)         { this.project = v; return this; }
        public Builder agentName(String v)       { this.agentName = v; return this; }
        public Builder environment(String v)     { this.environment = v; return this; }
        public Builder releaseVersion(String v)  { this.releaseVersion = v; return this; }
        public Builder flushIntervalMs(long v)   { this.flushIntervalMs = v; return this; }
        public Builder flushSize(int v)          { this.flushSize = v; return this; }
        public Builder autoPatch(boolean v)      { this.autoPatch = v; return this; }
        public Builder captureIo(boolean v)      { this.captureIo = v; return this; }

        public JamjetCloudConfig build() {
            return new JamjetCloudConfig(this);
        }
    }
}
