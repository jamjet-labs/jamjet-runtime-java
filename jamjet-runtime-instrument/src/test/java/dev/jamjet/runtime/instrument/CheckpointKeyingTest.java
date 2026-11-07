package dev.jamjet.runtime.instrument;

import dev.jamjet.runtime.instrument.annotations.Checkpoint;
import dev.jamjet.runtime.instrument.annotations.DurableAgent;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replay must return what THIS call returned the first time: same method, same
 * arguments, same position in the run. A call that threw must never come back as a
 * successful return.
 */
class CheckpointKeyingTest {

    static final List<String> RUNS = new ArrayList<>();

    @DurableAgent
    public static class Billing {
        @Checkpoint("charge")
        public String charge(String customer) {
            RUNS.add("charge:" + customer);
            return "receipt-" + customer;
        }

        @Checkpoint
        public int nextTicket() {
            RUNS.add("ticket");
            return RUNS.size();
        }

        @Checkpoint("refund")
        public String refund(String customer) {
            RUNS.add("refund:" + customer);
            throw new IllegalStateException("declined: " + customer);
        }

        /** Calls charge from inside another checkpoint. */
        @Checkpoint("batch")
        public String batch(String customer) {
            RUNS.add("batch:" + customer);
            return charge(customer) + "+batch";
        }

        @Checkpoint("send")
        public String send(Object payload) {
            RUNS.add("send");
            return "sent";
        }
    }

    /** No properties for Jackson to read and not a value type: no stable fingerprint. */
    public static final class Opaque {
        private final String secret;

        public Opaque(String secret) {
            this.secret = secret;
        }
    }

    @BeforeAll
    static void instrument() {
        ByteBuddyAgent.install();
        new ByteBuddy()
                .redefine(Billing.class)
                .visit(Advice.to(CheckpointInterceptor.class)
                        .on(ElementMatchers.isAnnotatedWith(Checkpoint.class)))
                .make()
                .load(Billing.class.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
    }

    @AfterAll
    static void revert() {
        new ByteBuddy()
                .redefine(Billing.class)
                .make()
                .load(Billing.class.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
    }

    @AfterEach
    void cleanup() {
        DurabilityContext.clear();
        RUNS.clear();
    }

    @Test
    void replayNeverReturnsAnotherArgumentsResult() {
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        Billing billing = new Billing();
        assertThat(billing.charge("alice")).isEqualTo("receipt-alice");

        ctx.setReplayMode(true);
        RUNS.clear();
        assertThat(billing.charge("bob")).isEqualTo("receipt-bob");
        assertThat(RUNS).containsExactly("charge:bob");
        assertThat(billing.charge("alice")).isEqualTo("receipt-alice");
        assertThat(RUNS).containsExactly("charge:bob");
    }

    @Test
    void repeatedCallsReplayInCallOrder() {
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        Billing billing = new Billing();
        assertThat(billing.nextTicket()).isEqualTo(1);
        assertThat(billing.nextTicket()).isEqualTo(2);

        ctx.setReplayMode(true);
        RUNS.clear();
        assertThat(billing.nextTicket()).isEqualTo(1);
        assertThat(billing.nextTicket()).isEqualTo(2);
        assertThat(RUNS).isEmpty();
    }

    @Test
    void aCallThatThrewNeverReplaysAsASuccess() {
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        Billing billing = new Billing();
        assertThatThrownBy(() -> billing.refund("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("declined: alice");

        ctx.setReplayMode(true);
        RUNS.clear();
        assertThatThrownBy(() -> billing.refund("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("declined: alice");
        assertThat(RUNS).isEmpty();
    }
    @Test
    void aNestedCheckpointDoesNotShiftLaterTopLevelCalls() {
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        Billing billing = new Billing();
        assertThat(billing.batch("alice")).isEqualTo("receipt-alice+batch");
        assertThat(billing.charge("alice")).isEqualTo("receipt-alice");

        // the second, top-level charge("alice") gets a different result this time
        ctx.restore(CheckpointRecord.success(topLevelChargeId(ctx), "second-receipt"));

        ctx.setReplayMode(true);
        RUNS.clear();
        // batch is skipped whole, so its inner charge never runs on replay; the top-level
        // charge must still replay the top-level result, not the inner one
        assertThat(billing.batch("alice")).isEqualTo("receipt-alice+batch");
        assertThat(billing.charge("alice")).isEqualTo("second-receipt");
        assertThat(RUNS).isEmpty();
    }

    /** The id the top-level charge was recorded under: the last one recorded. */
    private static String topLevelChargeId(DurabilityContext ctx) {
        return ctx.getCheckpointIds().stream()
                .filter(id -> id.startsWith("charge@"))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    @Test
    void aFailureRestoredFromStorageReplaysAsAFailure() {
        DurabilityContext recording = DurabilityContext.create();
        DurabilityContext.setCurrent(recording);
        Billing billing = new Billing();
        assertThatThrownBy(() -> billing.refund("alice")).isInstanceOf(IllegalStateException.class);
        CheckpointRecord saved = recording.getRecords().get(0);

        // only what a store could keep: id, type and message
        DurabilityContext replay = DurabilityContext.create();
        replay.restore(CheckpointRecord.failure(saved.id(), saved.failureType(), saved.failureMessage()));
        replay.setReplayMode(true);
        DurabilityContext.setCurrent(replay);
        RUNS.clear();

        assertThatThrownBy(() -> billing.refund("alice"))
                .isInstanceOf(CheckpointReplayException.class)
                .hasMessageContaining("java.lang.IllegalStateException")
                .hasMessageContaining("declined: alice");
        assertThat(RUNS).isEmpty();
    }

    @Test
    void anArgumentWithNoStableFingerprintIsRefusedBeforeTheBodyRuns() {
        DurabilityContext.setCurrent(DurabilityContext.create());
        Billing billing = new Billing();

        assertThatThrownBy(() -> billing.send(new Opaque("a")))
                .isInstanceOf(CheckpointKeyException.class)
                .hasMessageContaining("argument 0");
        assertThat(RUNS).isEmpty();

        // a Jackson-serializable argument is fine
        assertThat(billing.send(Map.of("to", "alice"))).isEqualTo("sent");
    }

    @Test
    void withoutAContextNothingIsFingerprinted() {
        assertThat(new Billing().send(new Opaque("a"))).isEqualTo("sent");
    }
}
