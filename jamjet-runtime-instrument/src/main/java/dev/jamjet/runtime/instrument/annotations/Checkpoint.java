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
 * Every argument must have a stable fingerprint: a value type, a record, a collection or
 * map of those, or an object with its own {@code equals} and {@code hashCode}. A client,
 * a session or a lambda passed as an argument is refused with
 * {@link dev.jamjet.runtime.instrument.CheckpointKeyException}; keep such objects in fields,
 * or call {@code DurabilityContext.replayOrExecute} with your own id.
 * </p>
 * <p>
 * A thrown {@link Exception} is recorded and replayed. An {@link Error} or an interrupt is not
 * recorded; a replay runs the call again.
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
