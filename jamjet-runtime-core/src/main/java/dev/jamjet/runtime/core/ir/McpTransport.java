package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum McpTransport {
    STDIO("stdio"),
    HTTP_SSE("http_sse"),
    WEB_SOCKET("web_socket");

    private final String value;

    McpTransport(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static McpTransport fromValue(String value) {
        for (McpTransport transport : values()) {
            if (transport.value.equals(value)) {
                return transport;
            }
        }
        throw new IllegalArgumentException("Unknown McpTransport: " + value);
    }
}
