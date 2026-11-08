package dev.jamjet.runtime.instrument;

import java.util.Objects;

/**
 * The recorded outcome of one checkpointed call: either the value it returned or the
 * failure it threw. A failure is never stored as a value, so it can never replay as a
 * successful return.
 *
 * <p>{@code failure} holds the original exception when the record was made in this
 * process. A record restored from storage carries only {@code failureType} and
 * {@code failureMessage}; replaying it throws a new instance of exactly {@code failureType}
 * built from the message when that class can be loaded and has a public {@code (String)},
 * {@code (String, Throwable)} or no-argument constructor, with a suppressed
 * {@link CheckpointReplayException} naming it as replayed. Otherwise the replay throws
 * {@link CheckpointReplayException} itself.</p>
 *
 * <p>An {@code indeterminate} record marks a call that threw something that is not an outcome
 * of the step (an {@link Error} or an interrupt). See {@link CheckpointRuntime} for how replay
 * treats it.</p>
 *
 * @param id             the checkpoint id the outcome is stored under
 * @param value          the returned value (always {@code null} for a failure)
 * @param failed         {@code true} when the call threw
 * @param indeterminate  {@code true} when what it threw is not an outcome of the step (an
 *                       {@link Error} or an interrupt); always a failure record too
 * @param failure        the thrown exception, when still available in this process
 * @param failureType    class name of the thrown exception ({@code null} for a success)
 * @param failureMessage message of the thrown exception (may be {@code null})
 */
public record CheckpointRecord(
        String id,
        Object value,
        boolean failed,
        boolean indeterminate,
        Throwable failure,
        String failureType,
        String failureMessage
) {

    public CheckpointRecord {
        Objects.requireNonNull(id, "id");
        if (indeterminate && !failed) {
            throw new IllegalArgumentException("an indeterminate marker is a failure record: " + id);
        }
        if (failed) {
            if (value != null) {
                throw new IllegalArgumentException("a failure record carries no value: " + id);
            }
            if (failure == null && failureType == null) {
                throw new IllegalArgumentException("a failure record needs the exception or its type: " + id);
            }
            if (failure != null && failureType == null) {
                failureType = failure.getClass().getName();
                failureMessage = failure.getMessage();
            }
        } else if (failure != null || failureType != null) {
            throw new IllegalArgumentException("a success record carries no failure: " + id);
        }
    }

    /** A call that returned {@code value}. */
    public static CheckpointRecord success(String id, Object value) {
        return new CheckpointRecord(id, value, false, false, null, null, null);
    }

    /** A call that threw {@code failure} in this process. */
    public static CheckpointRecord failure(String id, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return new CheckpointRecord(id, null, true, false, failure, null, null);
    }

    /** A failure restored from storage, where only the exception's type and message survive. */
    public static CheckpointRecord failure(String id, String failureType, String failureMessage) {
        Objects.requireNonNull(failureType, "failureType");
        return new CheckpointRecord(id, null, true, false, null, failureType, failureMessage);
    }

    /**
     * A call that threw {@code thrown}, which is not an outcome of the step (an
     * {@link Error} or an interrupt): its outcome is unknown.
     */
    public static CheckpointRecord indeterminate(String id, Throwable thrown) {
        Objects.requireNonNull(thrown, "thrown");
        return new CheckpointRecord(id, null, true, true, thrown, null, null);
    }

    /** An indeterminate marker restored from storage. */
    public static CheckpointRecord indeterminate(String id, String failureType, String failureMessage) {
        Objects.requireNonNull(failureType, "failureType");
        return new CheckpointRecord(id, null, true, true, null, failureType, failureMessage);
    }

    /**
     * The exception a replay of this failure throws: the original when it is still held,
     * otherwise a {@link CheckpointReplayException} naming the original type and message.
     */
    Throwable replayFailure() {
        if (!failed) {
            throw new IllegalStateException("not a failure record: " + id);
        }
        if (failure != null) {
            return failure;
        }
        CheckpointReplayException marker = new CheckpointReplayException(id, failureType, failureMessage);
        Throwable rebuilt = rebuild(failureType, failureMessage, indeterminate);
        if (rebuilt == null) {
            return marker;
        }
        rebuilt.addSuppressed(marker);
        return rebuilt;
    }

    /**
     * An instance of exactly {@code type} built from {@code message}, or {@code null}. A
     * failure record must name an {@link Exception}; an indeterminate marker may name any
     * {@link Throwable}. Never a superclass: rebuilding a {@code DeclinedException} as a plain
     * {@code IOException} could send a {@code catch} down its retry branch.
     */
    static Throwable rebuild(String type, String message, boolean anyThrowable) {
        Class<?> c = load(type);
        Class<?> bound = anyThrowable ? Throwable.class : Exception.class;
        if (c == null || !bound.isAssignableFrom(c)) {
            return null;
        }
        return construct(c, message);
    }

    private static Class<?> load(String type) {
        for (ClassLoader loader : new ClassLoader[]{
                Thread.currentThread().getContextClassLoader(), CheckpointRecord.class.getClassLoader()}) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(type, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                // try the next loader
            }
        }
        return null;
    }

    private static Throwable construct(Class<?> k, String message) {
        if (java.lang.reflect.Modifier.isAbstract(k.getModifiers())) {
            return null;
        }
        try {
            return (Throwable) k.getConstructor(String.class).newInstance(message);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // next shape
        }
        try {
            return (Throwable) k.getConstructor(String.class, Throwable.class).newInstance(message, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // next shape
        }
        try {
            return (Throwable) k.getConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
