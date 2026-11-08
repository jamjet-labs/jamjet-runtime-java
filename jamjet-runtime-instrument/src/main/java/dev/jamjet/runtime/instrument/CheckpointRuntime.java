package dev.jamjet.runtime.instrument;

import dev.jamjet.runtime.instrument.annotations.Checkpoint;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * What {@link CheckpointInterceptor}'s advice calls. The advice is inlined into the user's
 * {@code @DurableAgent} class, which can sit in any package, so everything it touches here
 * is public. Not a stable API: call it only from instrumented code.
 *
 * <p>A checkpoint id is {@code name#n@fingerprint}. {@code name} is the
 * {@link Checkpoint#value()} (or {@code com.example.Agent#method(param types)} when empty);
 * {@code n} is the call's position among calls with that name in the current pass; the
 * fingerprint covers the arguments. A replayed call is matched by {@code name#n}. When the
 * recorded fingerprint differs, the arguments changed between runs and the call is refused
 * with {@link CheckpointNondeterminismException}: it neither returns the other call's result
 * nor runs the body again.</p>
 *
 * <p>A checkpoint called from inside another one is scoped under the outer call's id, so
 * skipping the outer call on replay does not shift later top-level positions.</p>
 *
 * <p>What a position does not include: the instance the method was called on (two agent
 * objects calling {@code charge} share one sequence), and the thread. Positions are only
 * deterministic when one thread drives a context's checkpoints in the same order on every
 * run; a context shared by threads racing each other gets positions in whatever order they
 * win. Use one context per run and thread, or {@code replayOrExecute} with your own ids.</p>
 *
 * <p>What is recorded: the return value, or an {@link Exception} the call threw. An
 * {@link Error} (out of memory, a stack overflow) and an interrupt
 * ({@link InterruptedException}, an interrupted I/O call) are not outcomes of the step and
 * are not recorded; they propagate, and a replay runs the call again.</p>
 */
public final class CheckpointRuntime {

    private static final ThreadLocal<Pending> ACTIVE = new ThreadLocal<>();
    private static final Map<Method, String> NAMES = new ConcurrentHashMap<>();

    private CheckpointRuntime() {}

    /** Returned by {@link #enter} when the body must be skipped and {@code value} returned. */
    public static final class Replayed {
        private final Object value;

        Replayed(Object value) {
            this.value = value;
        }
    }

    /** A call whose body is running; its outcome is recorded on exit. */
    static final class Pending {
        final DurabilityContext context;
        final String id;
        final Pending previous;

        Pending(DurabilityContext context, String id, Pending previous) {
            this.context = context;
            this.id = id;
            this.previous = previous;
        }
    }

    /**
     * Called on method entry. Returns {@code null} when no {@link DurabilityContext} is
     * bound (the method runs untouched), a {@link Replayed} when the call was recorded
     * and the context is replaying, or a pending marker otherwise. Throws the recorded
     * failure when the call failed the first time.
     *
     * @throws CheckpointKeyException when an argument has no stable fingerprint
     */
    public static Object enter(Method method, Object[] args) {
        DurabilityContext ctx = DurabilityContext.current();
        if (ctx == null) {
            return null;
        }
        Pending outer = ACTIVE.get();
        String name = name(method);
        String fingerprint = ArgumentFingerprint.of(method, args);
        String scope = outer != null && outer.context == ctx ? outer.id + '/' + name : name;
        String position = scope + '#' + ctx.nextOccurrence(scope);
        String id = position + '@' + fingerprint;

        if (ctx.isReplayMode()) {
            CheckpointRecord recorded = ctx.getRecordAtPosition(position);
            if (recorded != null) {
                if (!recorded.id().equals(id)) {
                    throw new CheckpointNondeterminismException(position, recorded.id(), id);
                }
                if (recorded.failed()) {
                    throw sneakyThrow(recorded.replayFailure());
                }
                return new Replayed(recorded.value());
            }
        }
        Pending pending = new Pending(ctx, id, outer);
        ACTIVE.set(pending);
        return pending;
    }

    /**
     * Called on method exit, normal or exceptional. Records the outcome of a call whose
     * body ran (a throw is recorded as a failure, never as a value) and returns the value
     * the method should return.
     */
    public static Object exit(Object entered, Object returned, Throwable thrown) {
        if (entered instanceof Replayed replayed) {
            return replayed.value;
        }
        if (entered instanceof Pending pending) {
            try {
                if (thrown != null) {
                    if (recordable(thrown)) {
                        pending.context.recordFailure(pending.id, thrown);
                    }
                } else {
                    pending.context.recordResult(pending.id, returned);
                }
            } finally {
                if (pending.previous == null) {
                    ACTIVE.remove();
                } else {
                    ACTIVE.set(pending.previous);
                }
            }
        }
        return returned;
    }

    /**
     * Whether a throw is an outcome of the step, to be replayed as the same failure: an
     * {@link Exception} that is not an interrupt. An {@link Error} or an interrupt says
     * nothing about the step and would freeze a transient condition into the run forever.
     */
    static boolean recordable(Throwable thrown) {
        if (!(thrown instanceof Exception)) {
            return false;
        }
        if (thrown instanceof InterruptedException || thrown instanceof java.nio.channels.ClosedByInterruptException) {
            return false;
        }
        return !(thrown instanceof java.io.InterruptedIOException)
                || thrown instanceof java.net.SocketTimeoutException;
    }

    /** The checkpoint name: {@link Checkpoint#value()}, or the declaring class, method and parameter types. */
    static String name(Method method) {
        return NAMES.computeIfAbsent(method, m -> {
            Checkpoint ann = m.getAnnotation(Checkpoint.class);
            if (ann != null && !ann.value().isEmpty()) {
                return ann.value();
            }
            return m.getDeclaringClass().getName() + '#' + m.getName()
                    + Arrays.stream(m.getParameterTypes()).map(Class::getName)
                    .collect(Collectors.joining(",", "(", ")"));
        });
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
