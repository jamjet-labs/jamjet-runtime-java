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
 * and holds a recorded outcome for this call. If it holds a value, the method body is
 * skipped and the value is returned; if it holds a failure, the failure is thrown again.
 * Otherwise the body runs and its outcome, a value or a thrown exception, is recorded
 * on exit.</p>
 *
 * <p>Checkpoint ids are built by {@link CheckpointRuntime}: the checkpoint name, a
 * fingerprint of the arguments, and the call's position among calls with the same name
 * and arguments.</p>
 *
 * <p><strong>ByteBuddy constraints:</strong> the advice code is copied into the
 * instrumented class, which can be in any package, so it may only reference public
 * members. All logic lives in {@link CheckpointRuntime}. The body is skipped when the
 * enter advice returns a {@link CheckpointRuntime.Replayed}.</p>
 */
public final class CheckpointInterceptor {

    private CheckpointInterceptor() {}

    /**
     * Called before the instrumented method body.
     *
     * @param method the intercepted method (injected by ByteBuddy)
     * @param args   the call's arguments (injected by ByteBuddy)
     * @return a {@link CheckpointRuntime.Replayed} to skip the body, otherwise a value
     *         handed to {@link #onExit}
     */
    @Advice.OnMethodEnter(skipOn = CheckpointRuntime.Replayed.class)
    public static Object onEnter(
            @Advice.Origin Method method,
            @Advice.AllArguments Object[] args) {
        return CheckpointRuntime.enter(method, args);
    }

    /**
     * Called after the instrumented method body, after a throw, or after the skip.
     *
     * @param entered  the value returned by {@link #onEnter}
     * @param returned the value returned by the method body (writable)
     * @param thrown   the exception the body threw, or {@code null}
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Enter Object entered,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
            @Advice.Thrown Throwable thrown) {
        returned = CheckpointRuntime.exit(entered, returned, thrown);
    }
}
