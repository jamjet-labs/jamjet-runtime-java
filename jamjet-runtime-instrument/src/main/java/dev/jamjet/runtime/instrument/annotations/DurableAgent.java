package dev.jamjet.runtime.instrument.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a durable agent whose methods may be checkpointed and replayed.
 * When the JamJet Java agent is active, classes annotated with {@code @DurableAgent}
 * will be instrumented so that {@code @Checkpoint}-annotated methods participate
 * in replay-or-execute semantics via {@link dev.jamjet.runtime.instrument.DurabilityContext}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DurableAgent {

    /**
     * Optional logical name for this agent. Defaults to the simple class name when empty.
     */
    String value() default "";
}
