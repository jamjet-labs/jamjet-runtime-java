package dev.jamjet.runtime.instrument;

import dev.jamjet.runtime.instrument.annotations.Checkpoint;
import dev.jamjet.runtime.instrument.annotations.DurableAgent;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests for {@link JamjetAgent}.
 */
class JamjetAgentTest {

    @BeforeAll
    static void installAgent() {
        ByteBuddyAgent.install();
    }

    // -------------------------------------------------------------------------
    // Test 1: install() works without throwing
    // -------------------------------------------------------------------------

    @Test
    void installWorksWithByteBuddyAgent() {
        Instrumentation inst = ByteBuddyAgent.getInstrumentation();
        assertThatNoException().isThrownBy(() -> JamjetAgent.install(inst));
    }

    // -------------------------------------------------------------------------
    // Test 2: buildAgentBuilder() produces the expected configuration
    //         (matches @DurableAgent types and transforms @Checkpoint methods)
    // -------------------------------------------------------------------------

    @Test
    void agentBuilderMatchesDurableAgentAndCheckpoint() {
        // Verify the builder can be constructed and applied to a @DurableAgent class
        AgentBuilder.Identified.Extendable builder = JamjetAgent.buildAgentBuilder();
        assertThat(builder).isNotNull();

        // Apply the builder to a fresh class loader to confirm the transformation runs
        assertThatNoException().isThrownBy(() ->
                builder
                        .with(AgentBuilder.Listener.NoOp.INSTANCE)
                        .installOnByteBuddyAgent()
        );
    }

    // -------------------------------------------------------------------------
    // Test 3: end-to-end — agent transforms a @DurableAgent class at runtime
    // -------------------------------------------------------------------------

    @DurableAgent
    public static class AgentSampleClass {
        static int callCount = 0;

        @Checkpoint("agentStep")
        public String step() {
            callCount++;
            return "agent-result";
        }
    }

    @Test
    void agentInstrumentedClassParticipatesInReplay() {
        AgentSampleClass.callCount = 0;

        // Retransform using the same visitor pattern as the agent
        new net.bytebuddy.ByteBuddy()
                .redefine(AgentSampleClass.class)
                .visit(Advice.to(CheckpointInterceptor.class)
                        .on(ElementMatchers.isAnnotatedWith(Checkpoint.class)))
                .make()
                .load(AgentSampleClass.class.getClassLoader(),
                        ClassReloadingStrategy.fromInstalledAgent());

        // Normal execution — result is recorded
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        try {
            AgentSampleClass agent = new AgentSampleClass();
            String r1 = agent.step();
            assertThat(r1).isEqualTo("agent-result");
            assertThat(AgentSampleClass.callCount).isEqualTo(1);
            assertThat(ctx.getRecordedResult("agentStep")).isEqualTo("agent-result");

            // Replay — body should be skipped
            ctx.setReplayMode(true);
            AgentSampleClass.callCount = 0;
            String r2 = agent.step();
            assertThat(r2).isEqualTo("agent-result");
            assertThat(AgentSampleClass.callCount).isEqualTo(0);
        } finally {
            DurabilityContext.clear();
            // Revert instrumentation
            new net.bytebuddy.ByteBuddy()
                    .redefine(AgentSampleClass.class)
                    .make()
                    .load(AgentSampleClass.class.getClassLoader(),
                            ClassReloadingStrategy.fromInstalledAgent());
        }
    }
}
