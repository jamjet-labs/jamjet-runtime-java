package dev.jamjet.cloud.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jamjet.cloud")
public class JamjetCloudProperties {

    private String apiKey;
    private String apiUrl = "https://api.jamjet.dev";
    private String project = "default";
    private boolean enabled = true;
    private boolean captureIo = false;
    private boolean autoPatch = true;

    private final Agent agent = new Agent();
    private final Batch batch = new Batch();

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isCaptureIo() { return captureIo; }
    public void setCaptureIo(boolean captureIo) { this.captureIo = captureIo; }
    public boolean isAutoPatch() { return autoPatch; }
    public void setAutoPatch(boolean autoPatch) { this.autoPatch = autoPatch; }
    public Agent getAgent() { return agent; }
    public Batch getBatch() { return batch; }

    public static class Agent {
        private String name;
        private String cardUri;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCardUri() { return cardUri; }
        public void setCardUri(String cardUri) { this.cardUri = cardUri; }
    }

    public static class Batch {
        private long intervalMs = 5_000L;
        private int size = 50;
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
    }
}
