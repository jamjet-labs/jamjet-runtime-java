package dev.jamjet.runtime.instrument.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a checkpoint within a {@link DurableAgent}.
 * <p>
 * When a {@link dev.jamjet.runtime.instrument.DurabilityContext} is active, the outcome of
 * the annotated method is recorded on first execution: the returned value, or the exception
 * it threw. On replay, a stored value is returned without re-executing the method body, and a
 * stored failure is thrown again. Requires the JamJet javaagent.
 * </p>
 * <p>
 * Each call is recorded under {@code name#n@fingerprint}: the checkpoint name, the call's
 * position among calls with that name, and a fingerprint of the arguments. Replay matches by
 * name and position, and refuses a call whose arguments differ from the recorded ones with
 * {@link dev.jamjet.runtime.instrument.CheckpointNondeterminismException}. Compute values
 * that change between runs (random ids, the time) inside a checkpoint so they replay too.
 * </p>
 * <p>
 * Every argument must have a stable fingerprint: a JDK value type from a fixed list
 * ({@code String}, numbers, enums, {@code UUID}, {@code URI}, {@code URL}, {@code java.time}),
 * a record, a JDK collection or map of those, or your own object with {@code equals} and
 * {@code hashCode} (read by its fields; no method is called). A client, a session, a lambda,
 * or another JDK type is refused with
 * {@link dev.jamjet.runtime.instrument.CheckpointKeyException}; keep such objects in fields,
 * or call {@code DurabilityContext.replayOrExecute} with your own id.
 * </p>
 * <p>
 * A thrown {@link Exception} is recorded and replayed. An {@link Error} or an interrupt is
 * recorded as an indeterminate marker: replay throws it again where the first run went on
 * past it, and runs the call again where the run stopped.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Checkpoint {

    /**
     * Optional stable name for this checkpoint. When empty, the name defaults to the
     * declaring class's fully qualified name, the method name and its parameter types.
     */
    String value() default "";
}
