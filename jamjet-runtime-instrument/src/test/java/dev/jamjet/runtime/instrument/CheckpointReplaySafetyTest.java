package dev.jamjet.runtime.instrument;

import dev.jamjet.runtime.instrument.annotations.Checkpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replay safety cases from review, driving {@link CheckpointRuntime} the way the inlined
 * advice does.
 */
class CheckpointReplaySafetyTest {

    static final AtomicInteger CHARGES = new AtomicInteger();

    @Checkpoint("charge")
    public static String charge(String customer, String idempotencyKey) {
        CHARGES.incrementAndGet();
        return "charged " + customer;
    }

    @Checkpoint("declinable")
    public static String declinable(String customer) {
        throw new IllegalStateException("declined " + customer);
    }

    @Checkpoint("tagged")
    public static String tagged(Object arg) {
        return "ok";
    }

    /** A value type: equals and hashCode, a set inside. */
    public static final class Tags {
        private final Set<String> tags;

        Tags(Set<String> tags) {
            this.tags = tags;
        }

        public Set<String> getTags() {
            return tags;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Tags t && t.tags.equals(tags);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tags);
        }
    }

    /** No equals or hashCode: identity semantics. */
    public static final class Session {
        private final String user;

        Session(String user) {
            this.user = user;
        }

        public String getUser() {
            return user;
        }
    }

    static Object call(Method m, Object[] args, Supplier<Object> body) {
        Object entered = CheckpointRuntime.enter(m, args);
        if (entered instanceof CheckpointRuntime.Replayed) {
            return CheckpointRuntime.exit(entered, null, null);
        }
        Object ret;
        try {
            ret = body.get();
        } catch (RuntimeException | Error t) {
            CheckpointRuntime.exit(entered, null, t);
            throw t;
        }
        return CheckpointRuntime.exit(entered, ret, null);
    }

    private static DurabilityContext replayOf(DurabilityContext source, boolean typeAndMessageOnly) {
        DurabilityContext replay = DurabilityContext.create();
        for (CheckpointRecord r : source.getRecords()) {
            replay.restore(typeAndMessageOnly && r.failed()
                    ? CheckpointRecord.failure(r.id(), r.failureType(), r.failureMessage()) : r);
        }
        replay.setReplayMode(true);
        return replay;
    }

    @AfterEach
    void clear() {
        DurabilityContext.clear();
        CHARGES.set(0);
    }

    @Test
    void changedArgumentsOnReplayAreRefusedNotReRun() throws Exception {
        Method m = getClass().getMethod("charge", String.class, String.class);
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        String key1 = UUID.randomUUID().toString();
        call(m, new Object[]{"alice", key1}, () -> charge("alice", key1));

        DurabilityContext.setCurrent(replayOf(ctx, false));
        String key2 = UUID.randomUUID().toString();
        assertThatThrownBy(() -> call(m, new Object[]{"alice", key2}, () -> charge("alice", key2)))
                .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo("CheckpointNondeterminismException"));
        assertThat(CHARGES.get()).as("the side effect must not run a second time").isEqualTo(1);
    }

    @Test
    void aRestoredFailureReplaysAsItsOriginalType() throws Exception {
        Method m = getClass().getMethod("declinable", String.class);
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        assertThatThrownBy(() -> call(m, new Object[]{"bob"}, () -> declinable("bob")))
                .isInstanceOf(IllegalStateException.class);

        DurabilityContext.setCurrent(replayOf(ctx, true));
        assertThatThrownBy(() -> call(m, new Object[]{"bob"}, () -> declinable("bob")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("declined bob");
    }

    @Test
    void anErrorIsNotRecordedAsAnOutcome() throws Exception {
        Method m = getClass().getMethod("declinable", String.class);
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        Object entered = CheckpointRuntime.enter(m, new Object[]{"carol"});
        CheckpointRuntime.exit(entered, null, new OutOfMemoryError("transient"));
        assertThat(ctx.getRecords()).singleElement().satisfies(r -> assertThat(r.indeterminate()).isTrue());

        // where the run stopped: the outcome is unknown, so the call runs again
        ctx.setReplayMode(true);
        Object again = CheckpointRuntime.enter(m, new Object[]{"carol"});
        assertThat(again).as("the body runs again").isNotInstanceOf(CheckpointRuntime.Replayed.class);
    }

    @Test
    void anInterruptIsNotRecorded() throws Exception {
        Method m = getClass().getMethod("declinable", String.class);
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        Object entered = CheckpointRuntime.enter(m, new Object[]{"dave"});
        CheckpointRuntime.exit(entered, null, new InterruptedException("shutdown"));
        assertThat(ctx.getRecords()).singleElement().satisfies(r -> {
            assertThat(r.indeterminate()).isTrue();
            assertThat(r.failureType()).isEqualTo("java.lang.InterruptedException");
        });
    }

    @Test
    void equalSetsInsideAValueObjectFingerprintTheSame() throws Exception {
        Method m = getClass().getMethod("tagged", Object.class);
        List<String> words = List.of("Aa", "BB", "x", "y", "z", "q17", "tag-1", "tag-2", "tag-3");
        Set<String> small = new HashSet<>();
        Set<String> big = new HashSet<>(1024);
        words.forEach(small::add);
        for (int i = words.size() - 1; i >= 0; i--) {
            big.add(words.get(i));
        }
        assertThat(ArgumentFingerprint.of(m, new Object[]{new Tags(small)}))
                .isEqualTo(ArgumentFingerprint.of(m, new Object[]{new Tags(big)}));
    }

    @Test
    void anObjectWithIdentitySemanticsIsRefused() throws Exception {
        Method m = getClass().getMethod("tagged", Object.class);
        assertThatThrownBy(() -> ArgumentFingerprint.of(m, new Object[]{new Session("alice")}))
                .isInstanceOf(CheckpointKeyException.class);
        Runnable lambda = () -> {};
        assertThatThrownBy(() -> ArgumentFingerprint.of(m, new Object[]{lambda}))
                .isInstanceOf(CheckpointKeyException.class);
    }

    /** No public constructor that takes a message. */
    public static final class CodedDecline extends IllegalStateException {
        public CodedDecline(int code) {
            super("code " + code);
        }
    }

    @Test
    void aFailureWhoseExactTypeCannotBeBuiltReplaysAsCheckpointReplayException() {
        Throwable t = CheckpointRecord.failure("x#0@" + "0".repeat(32), CodedDecline.class.getName(), "code 7")
                .replayFailure();
        assertThat(t).isExactlyInstanceOf(CheckpointReplayException.class);
        assertThat(((CheckpointReplayException) t).originalType()).isEqualTo(CodedDecline.class.getName());
    }

    @Test
    void aFailureWhoseTypeCannotBeLoadedReplaysAsCheckpointReplayException() {
        assertThat(CheckpointRecord.failure("x", "com.example.Gone", "m").replayFailure())
                .isInstanceOf(CheckpointReplayException.class);
        // never an Error, even if the stored type names one
        assertThat(CheckpointRecord.failure("x", "java.lang.OutOfMemoryError", "m").replayFailure())
                .isInstanceOf(CheckpointReplayException.class);
    }

    @Test
    void aSocketTimeoutIsRecordedButAnInterruptedReadIsNot() {
        assertThat(CheckpointRuntime.recordable(new java.net.SocketTimeoutException())).isTrue();
        assertThat(CheckpointRuntime.recordable(new java.io.InterruptedIOException())).isFalse();
        assertThat(CheckpointRuntime.recordable(new java.nio.channels.ClosedByInterruptException())).isFalse();
        assertThat(CheckpointRuntime.recordable(new StackOverflowError())).isFalse();
        assertThat(CheckpointRuntime.recordable(new IllegalStateException())).isTrue();
    }
}
