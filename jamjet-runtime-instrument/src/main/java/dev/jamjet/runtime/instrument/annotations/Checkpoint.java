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
 * Each call is recorded under its own id: the checkpoint name, a fingerprint of the
 * arguments, and the call's position among calls with the same name and arguments. Every
 * argument must have a stable fingerprint (a value type, a record, a collection or map of
 * those, or a Jackson-serializable object); otherwise the call is refused with
 * {@link dev.jamjet.runtime.instrument.CheckpointKeyException}.
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
