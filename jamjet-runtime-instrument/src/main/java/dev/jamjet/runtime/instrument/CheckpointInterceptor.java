package dev.jamjet.runtime.instrument;

import dev.jamjet.runtime.instrument.annotations.Checkpoint;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;

/**
 * ByteBuddy {@link Advice} class that implements checkpoint semantics for methods
 * annotated with {@link Checkpoint}.
 *
 * <p>On method entry, the interceptor checks whether a {@link DurabilityContext} is active
 * and holds a cached result for this checkpoint. If so, the method body is skipped and
 * the cached value is returned. On method exit, the return value is recorded into the
 * active context (when one is present).</p>
 *
 * <p>Checkpoint ID resolution: uses {@link Checkpoint#value()} when non-empty; otherwise
 * falls back to {@code ClassName.methodName}.</p>
 *
 * <p><strong>ByteBuddy constraints:</strong> All {@code @Advice} methods must be {@code static}.
 * The {@code skipOn = Advice.OnNonDefaultValue.class} parameter in
 * {@link Advice.OnMethodEnter} causes the method body to be skipped when the enter method
 * returns a non-null value. The cached result is stored in a thread-local so the exit
 * advice can use it to override the return value.</p>
 */
public final class CheckpointInterceptor {

    /**
     * Thread-local that carries the cached value from enter to exit when the method body
     * is being skipped. Cleared by the exit advice immediately after use.
     */
    static final ThreadLocal<Object> SKIP_VALUE = new ThreadLocal<>();

    private CheckpointInterceptor() {}

    // -------------------------------------------------------------------------
    // Advice: enter
    // -------------------------------------------------------------------------

    /**
     * Called before the instrumented method body.
     *
     * @param method    the intercepted method (injected by ByteBuddy)
     * @return non-null when the method body should be skipped (cached result exists
     *         in replay mode), {@code null} otherwise
     */
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(@Advice.Origin Method method) {
        DurabilityContext ctx = DurabilityContext.current();
        if (ctx == null) {
            return null;
        }
        String checkpointId = resolveCheckpointId(method);
        if (ctx.isReplayMode() && ctx.getRecordedResult(checkpointId) != null) {
            Object cached = ctx.getRecordedResult(checkpointId);
            SKIP_VALUE.set(cached);
            // return non-null sentinel to trigger skipOn
            return Boolean.TRUE;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Advice: exit
    // -------------------------------------------------------------------------

    /**
     * Called after the instrumented method body (or after the skip).
     *
     * @param method    the intercepted method (injected by ByteBuddy)
     * @param returned  the value returned by the method body (writable)
     * @param skipped   the value returned by the enter advice ({@code null} means normal exit)
     */
    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Origin Method method,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
            @Advice.Enter Object skipped) {

        if (skipped != null) {
            // method was skipped — restore the cached value as the return value
            Object cached = SKIP_VALUE.get();
            SKIP_VALUE.remove();
            returned = cached;
            return;
        }

        // normal execution path — record the result
        DurabilityContext ctx = DurabilityContext.current();
        if (ctx != null) {
            String checkpointId = resolveCheckpointId(method);
            ctx.recordResult(checkpointId, returned);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static String resolveCheckpointId(Method method) {
        Checkpoint ann = method.getAnnotation(Checkpoint.class);
        if (ann != null && !ann.value().isEmpty()) {
            return ann.value();
        }
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
