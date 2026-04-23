package dev.jamjet.runtime.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public record ErrorClass(@JsonValue String value) {

    public static final ErrorClass IO_ERROR = new ErrorClass("io_error");
    public static final ErrorClass TIMEOUT = new ErrorClass("timeout");
    public static final ErrorClass RATE_LIMIT = new ErrorClass("rate_limit");
    public static final ErrorClass SERVER_ERROR = new ErrorClass("server_error");
    public static final ErrorClass CONNECTION_RESET = new ErrorClass("connection_reset");

    @JsonCreator
    public static ErrorClass custom(String value) {
        return new ErrorClass(value);
    }
}
