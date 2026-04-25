package dev.jamjet.runtime.instrument;

import dev.jamjet.runtime.instrument.annotations.Checkpoint;
import dev.jamjet.runtime.instrument.annotations.DurableAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point for the JamJet bytecode instrumentation layer.
 *
 * <p>When attached as a Java agent ({@code -javaagent:jamjet-runtime-instrument.jar}),
 * this class installs a ByteBuddy {@link AgentBuilder} that:
 * <ol>
 *   <li>Matches every class annotated with {@link DurableAgent}</li>
 *   <li>Applies {@link CheckpointInterceptor} advice to every method annotated
 *       with {@link Checkpoint} in those classes</li>
 * </ol>
 *
 * <p>The agent manifest attributes {@code Premain-Class} and {@code Can-Retransform-Classes}
 * are set in the module's {@code pom.xml} {@code maven-jar-plugin} configuration.</p>
 */
public final class JamjetAgent {

    private static final Logger log = LoggerFactory.getLogger(JamjetAgent.class);

    private JamjetAgent() {}

    // -------------------------------------------------------------------------
    // Agent entry points
    // -------------------------------------------------------------------------

    /**
     * Called by the JVM when the agent is specified on the command line via
     * {@code -javaagent:jamjet-runtime-instrument.jar[=args]}.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        log.info("JamJet instrumentation agent starting (premain), args={}", agentArgs);
        install(instrumentation);
    }

    /**
     * Called by the JVM when the agent is attached dynamically at runtime via
     * the Attach API.
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        log.info("JamJet instrumentation agent starting (agentmain), args={}", agentArgs);
        install(instrumentation);
    }

    // -------------------------------------------------------------------------
    // Core installation logic (package-private for testability)
    // -------------------------------------------------------------------------

    /**
     * Installs the ByteBuddy agent builder onto the provided {@link Instrumentation}.
     *
     * <p>This method is package-private so that tests can call it directly without
     * going through the full premain entry point.</p>
     *
     * @param instrumentation the JVM instrumentation handle
     */
    static AgentBuilder.Identified.Extendable buildAgentBuilder() {
        return new AgentBuilder.Default()
                .type(ElementMatchers.isAnnotatedWith(DurableAgent.class))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(CheckpointInterceptor.class)
                                        .on(ElementMatchers.isAnnotatedWith(Checkpoint.class))
                        )
                );
    }

    static void install(Instrumentation instrumentation) {
        buildAgentBuilder()
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .installOn(instrumentation);
        log.info("JamJet instrumentation agent installed successfully");
    }
}
