package dev.jamjet.runtime.instrument;

import dev.jamjet.runtime.instrument.annotations.Checkpoint;
import dev.jamjet.runtime.instrument.annotations.DurableAgent;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CheckpointInterceptor} using the ByteBuddy programmatic API.
 *
 * <p>We use {@link ClassReloadingStrategy} (which requires the ByteBuddy Java agent) to
 * retransform the sample class in-place so the tests operate on the same class reference.</p>
 */
class CheckpointInterceptorTest {

    /** Simple counter so tests can detect whether the method body ran. */
    static final AtomicInteger CALL_COUNT = new AtomicInteger(0);

    @BeforeAll
    static void installAgent() {
        // Install the ByteBuddy agent so we can use ClassReloadingStrategy
        ByteBuddyAgent.install();
    }

    @AfterEach
    void cleanup() {
        DurabilityContext.clear();
        CALL_COUNT.set(0);
        // Revert instrumentation so each test starts with an un-instrumented class
        new ByteBuddy()
                .redefine(SampleAgent.class)
                .make()
                .load(SampleAgent.class.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
    }

    // -------------------------------------------------------------------------
    // Sample durable agent under test
    // -------------------------------------------------------------------------

    @DurableAgent
    public static class SampleAgent {
        @Checkpoint("compute")
        public String compute() {
            CALL_COUNT.incrementAndGet();
            return "result";
        }
    }

    // -------------------------------------------------------------------------
    // Helper: instrument SampleAgent with CheckpointInterceptor
    // -------------------------------------------------------------------------

    private static void instrument() {
        new ByteBuddy()
                .redefine(SampleAgent.class)
                .visit(Advice.to(CheckpointInterceptor.class)
                        .on(ElementMatchers.isAnnotatedWith(Checkpoint.class)))
                .make()
                .load(SampleAgent.class.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void withoutContext_methodExecutesNormally() throws Exception {
        instrument();

        SampleAgent agent = new SampleAgent();
        String result = agent.compute();

        assertThat(result).isEqualTo("result");
        assertThat(CALL_COUNT.get()).isEqualTo(1);
    }

    @Test
    void withContextInNormalMode_resultIsRecorded() throws Exception {
        instrument();

        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);

        SampleAgent agent = new SampleAgent();
        String result = agent.compute();

        assertThat(result).isEqualTo("result");
        assertThat(CALL_COUNT.get()).isEqualTo(1);
        assertThat(ctx.getRecordedResult("compute")).isEqualTo("result");
    }

    @Test
    void withContextInReplayMode_storedResultReturnedWithoutCallingBody() throws Exception {
        instrument();

        DurabilityContext ctx = DurabilityContext.create();
        ctx.recordResult("compute", "replayed-result");
        ctx.setReplayMode(true);
        DurabilityContext.setCurrent(ctx);

        SampleAgent agent = new SampleAgent();
        String result = agent.compute();

        assertThat(result).isEqualTo("replayed-result");
        assertThat(CALL_COUNT.get()).isEqualTo(0);
    }
}
