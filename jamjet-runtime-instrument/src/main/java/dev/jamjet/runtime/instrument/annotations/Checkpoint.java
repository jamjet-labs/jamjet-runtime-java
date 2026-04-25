package dev.jamjet.runtime.instrument.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a checkpoint within a {@link DurableAgent}.
 * <p>
 * When a {@link dev.jamjet.runtime.instrument.DurabilityContext} is active, the return
 * value of the annotated method will be recorded on first execution. On replay, the stored
 * value is returned immediately without re-executing the method body.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Checkpoint {

    /**
     * Optional stable identifier for this checkpoint. When empty, the checkpoint ID
     * defaults to {@code ClassName.methodName}.
     */
    String value() default "";
}
