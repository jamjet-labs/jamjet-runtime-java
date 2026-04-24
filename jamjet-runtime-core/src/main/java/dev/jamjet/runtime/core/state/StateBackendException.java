package dev.jamjet.runtime.core.state;

public class StateBackendException extends Exception {

    public enum Kind {
        NOT_FOUND,
        SEQUENCE_CONFLICT,
        DATABASE,
        SERIALIZATION
    }

    private final Kind kind;

    public StateBackendException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public StateBackendException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public static StateBackendException notFound(String id) {
        return new StateBackendException(Kind.NOT_FOUND, "Not found: " + id);
    }

    public static StateBackendException database(String msg) {
        return new StateBackendException(Kind.DATABASE, msg);
    }

    public static StateBackendException database(String msg, Throwable cause) {
        return new StateBackendException(Kind.DATABASE, msg, cause);
    }
}
