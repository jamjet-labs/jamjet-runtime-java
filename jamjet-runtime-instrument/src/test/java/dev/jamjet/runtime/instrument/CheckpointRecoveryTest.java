package dev.jamjet.runtime.instrument;

import com.sun.net.httpserver.HttpServer;
import dev.jamjet.runtime.instrument.annotations.Checkpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Re-review cases: agent code that catches broadly, retries, and restores from storage
 * must not get a checkpointed side effect to run twice.
 */
class CheckpointRecoveryTest {

    static final AtomicInteger RUNS = new AtomicInteger();

    @Checkpoint("charge")
    public static String charge(String customer, String key) {
        return "x";
    }

    @Checkpoint("wrap")
    public static String wrap(String job) {
        return "x";
    }

    /** Declined is final for the agent; any other IOException is retried. */
    public static class DeclinedException extends IOException {
        public DeclinedException(String code, int amount) {
            super("declined " + code);
        }
    }

    /** A value object whose getter has a side effect. */
    public static final class Order {
        static final AtomicInteger GETTER_CALLS = new AtomicInteger();
        private final String sku;

        Order(String sku) {
            this.sku = sku;
        }

        public String getSku() {
            GETTER_CALLS.incrementAndGet();
            return sku;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Order other && other.sku.equals(sku);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sku);
        }
    }

    static final class Wrapped extends RuntimeException {
        Wrapped(Throwable t) {
            super(t);
        }
    }

    /** What the advice does around a call; a Wrapped from the body stands for a checked throw. */
    static Object call(Method m, Object[] args, Supplier<Object> body) throws Throwable {
        Object entered = CheckpointRuntime.enter(m, args);
        if (entered instanceof CheckpointRuntime.Replayed) {
            return CheckpointRuntime.exit(entered, null, null);
        }
        Object ret;
        try {
            ret = body.get();
        } catch (Throwable t) {
            Throwable real = t instanceof Wrapped w ? w.getCause() : t;
            CheckpointRuntime.exit(entered, null, real);
            throw real;
        }
        return CheckpointRuntime.exit(entered, ret, null);
    }

    static Method charge() throws Exception {
        return CheckpointRecoveryTest.class.getMethod("charge", String.class, String.class);
    }

    static DurabilityContext replayOf(DurabilityContext first, boolean failuresAsText) {
        DurabilityContext r = DurabilityContext.create();
        for (CheckpointRecord rec : first.getRecords()) {
            r.restore(failuresAsText && rec.failed()
                    ? CheckpointRecord.failure(rec.id(), rec.failureType(), rec.failureMessage()) : rec);
        }
        r.setReplayMode(true);
        DurabilityContext.setCurrent(r);
        return r;
    }

    /** Charges with a fresh key and retries on anything, three times. */
    static void chargeRetryingOnAnything(Method m) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String key = UUID.randomUUID().toString();
            try {
                call(m, new Object[]{"alice", key}, () -> {
                    RUNS.incrementAndGet();
                    return "ok";
                });
                return;
            } catch (Throwable swallowed) {
                // a broad catch, as agent code often has
            }
        }
    }

    @AfterEach
    void clear() {
        DurabilityContext.clear();
        RUNS.set(0);
        Order.GETTER_CALLS.set(0);
    }

    @Test
    void aSwallowedNondeterminismErrorDoesNotLetARetryRunTheSideEffect() throws Exception {
        Method m = charge();
        DurabilityContext.setCurrent(DurabilityContext.create());
        chargeRetryingOnAnything(m);
        replayOf(DurabilityContext.current(), false);

        chargeRetryingOnAnything(m);

        assertThat(RUNS.get()).as("the charge ran once, in the first run").isEqualTo(1);
    }

    @Test
    void theNondeterminismErrorIsNotAnException() throws Throwable {
        Method m = charge();
        DurabilityContext.setCurrent(DurabilityContext.create());
        call(m, new Object[]{"alice", "k1"}, () -> "ok");
        replayOf(DurabilityContext.current(), false);
        Throwable t = null;
        try {
            call(m, new Object[]{"alice", "k2"}, () -> "ok");
        } catch (Throwable e) {
            t = e;
        }
        assertThat(t).isNotNull().isNotInstanceOf(Exception.class);
    }

    @Test
    void anUnrecordedInterruptLeavesNoHoleForReplayToFill() throws Throwable {
        Method m = charge();
        DurabilityContext.setCurrent(DurabilityContext.create());
        AtomicInteger n = new AtomicInteger();
        Runnable agent = () -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    call(m, new Object[]{"carol", "fixed"}, () -> {
                        RUNS.incrementAndGet();
                        if (n.getAndIncrement() == 0) {
                            throw new Wrapped(new InterruptedIOException("interrupted read"));
                        }
                        return "ok";
                    });
                    return;
                } catch (IOException e) {
                    // retry
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
        };
        agent.run();
        assertThat(RUNS.get()).isEqualTo(2);

        replayOf(DurabilityContext.current(), false);
        agent.run();
        assertThat(RUNS.get()).as("replay follows the recorded path: no third run").isEqualTo(2);
    }

    @Test
    void aMissingPositionBeforeARecordedOneIsRefused() throws Throwable {
        Method m = charge();
        DurabilityContext first = DurabilityContext.create();
        DurabilityContext.setCurrent(first);
        call(m, new Object[]{"dan", "k"}, () -> "ok");
        call(m, new Object[]{"dan", "k"}, () -> "ok");
        DurabilityContext replay = DurabilityContext.create();
        replay.restore(first.getRecords().get(1));   // #0 lost
        replay.setReplayMode(true);
        DurabilityContext.setCurrent(replay);

        assertThatThrownBy(() -> call(m, new Object[]{"dan", "k"}, () -> {
            RUNS.incrementAndGet();
            return "ok";
        })).satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo("CheckpointNondeterminismException"));
        assertThat(RUNS.get()).isZero();
    }

    @Test
    void aRestoredFailureIsRebuiltOnlyAsItsExactClass() throws Throwable {
        Method m = charge();
        DurabilityContext.setCurrent(DurabilityContext.create());
        assertThatThrownBy(() -> call(m, new Object[]{"bob", "k"}, () -> {
            throw new Wrapped(new DeclinedException("51", 10));
        })).isInstanceOf(DeclinedException.class);
        replayOf(DurabilityContext.current(), true);

        // DeclinedException has no (String) constructor. Rebuilding it as a plain IOException
        // would send the agent down its retry branch and make a new real call.
        assertThatThrownBy(() -> call(m, new Object[]{"bob", "k"}, () -> "ok"))
                .isNotInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo("CheckpointReplayException"));
    }

    @Test
    void aUrlArgumentIsNotFetched() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });
        server.start();
        try {
            URL url = new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/doc");
            Method m = charge();
            String a = ArgumentFingerprint.of(m, new Object[]{url, "k"});
            assertThat(ArgumentFingerprint.of(m, new Object[]{url, "k"})).isEqualTo(a);
            assertThat(hits.get()).as("fingerprinting must not touch the network").isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aValueObjectIsReadFromItsFieldsNotItsGetters() throws Exception {
        Method m = charge();
        String a = ArgumentFingerprint.of(m, new Object[]{new Order("sku-1"), "k"});
        assertThat(ArgumentFingerprint.of(m, new Object[]{new Order("sku-1"), "k"})).isEqualTo(a);
        assertThat(ArgumentFingerprint.of(m, new Object[]{new Order("sku-2"), "k"})).isNotEqualTo(a);
        assertThat(Order.GETTER_CALLS.get()).isZero();
    }

    @Test
    void aJdkTypeOutsideTheAllowListIsRefused() throws Exception {
        Method m = charge();
        assertThatThrownBy(() -> ArgumentFingerprint.of(m, new Object[]{new Date(0), "k"}))
                .isInstanceOf(CheckpointKeyException.class);
        assertThatThrownBy(() -> ArgumentFingerprint.of(m, new Object[]{new StringBuilder("x"), "k"}))
                .isInstanceOf(CheckpointKeyException.class);
    }

    @Test
    void nothingIsRecordedAfterTheContextDiverged() throws Throwable {
        Method wrap = CheckpointRecoveryTest.class.getMethod("wrap", String.class);
        Method m = charge();
        DurabilityContext first = DurabilityContext.create();
        DurabilityContext.setCurrent(first);
        call(wrap, new Object[]{"job"}, () -> {
            try {
                return call(m, new Object[]{"alice", "k1"}, () -> "ok");
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        // keep the inner record only, so the outer call runs again on replay
        DurabilityContext replay = DurabilityContext.create();
        first.getRecords().stream().filter(r -> r.id().contains("/")).forEach(replay::restore);
        replay.setReplayMode(true);
        DurabilityContext.setCurrent(replay);

        Object out = call(wrap, new Object[]{"job"}, () -> {
            try {
                return call(m, new Object[]{"alice", "k2"}, () -> "ok");
            } catch (Throwable swallowed) {
                return "fallback";   // agent code that swallows everything
            }
        });

        assertThat(out).isEqualTo("fallback");
        assertThat(replay.hasDiverged()).isTrue();
        assertThat(replay.getCheckpointIds()).noneMatch(id -> id.startsWith("wrap#0@") && !id.contains("/"));
    }
}
