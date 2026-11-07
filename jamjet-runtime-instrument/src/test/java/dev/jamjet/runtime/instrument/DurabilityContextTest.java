package dev.jamjet.runtime.instrument;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurabilityContextTest {

    @AfterEach
    void cleanup() {
        DurabilityContext.clear();
    }

    @Test
    void normalExecutionCallsSupplierAndStoresResult() {
        DurabilityContext ctx = DurabilityContext.create();
        AtomicInteger callCount = new AtomicInteger(0);

        String result = ctx.replayOrExecute("step1", () -> {
            callCount.incrementAndGet();
            return "hello";
        });

        assertThat(result).isEqualTo("hello");
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(ctx.getRecordedResult("step1")).isEqualTo("hello");
    }

    @Test
    void replayModeReturnsStoredResultWithoutCallingSupplier() {
        DurabilityContext ctx = DurabilityContext.create();
        ctx.recordResult("step1", "cached-value");
        ctx.setReplayMode(true);

        AtomicInteger callCount = new AtomicInteger(0);
        String result = ctx.replayOrExecute("step1", () -> {
            callCount.incrementAndGet();
            return "fresh-value";
        });

        assertThat(result).isEqualTo("cached-value");
        assertThat(callCount.get()).isEqualTo(0);
    }

    @Test
    void replayModeFallsBackToExecutionWhenNoStoredResult() {
        DurabilityContext ctx = DurabilityContext.create();
        ctx.setReplayMode(true);

        AtomicInteger callCount = new AtomicInteger(0);
        String result = ctx.replayOrExecute("step-new", () -> {
            callCount.incrementAndGet();
            return "computed";
        });

        assertThat(result).isEqualTo("computed");
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(ctx.getRecordedResult("step-new")).isEqualTo("computed");
    }

    @Test
    void currentReturnsNullWhenNotSet() {
        // ensure nothing is bound on this thread
        DurabilityContext.clear();
        assertThat(DurabilityContext.current()).isNull();
    }

    @Test
    void checkpointIdsAreTrackedInOrder() {
        DurabilityContext ctx = DurabilityContext.create();

        ctx.replayOrExecute("alpha", () -> 1);
        ctx.replayOrExecute("beta", () -> 2);
        ctx.replayOrExecute("gamma", () -> 3);

        assertThat(ctx.getCheckpointIds()).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void clearRemovesThreadLocal() {
        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);
        assertThat(DurabilityContext.current()).isSameAs(ctx);

        DurabilityContext.clear();
        assertThat(DurabilityContext.current()).isNull();
    }
    @Test
    void aRecordedFailureIsNotReadableAsAResult() {
        DurabilityContext ctx = DurabilityContext.create();
        ctx.recordFailure("pay", new IllegalStateException("declined"));

        assertThat(ctx.getCheckpointIds()).containsExactly("pay");
        assertThatThrownBy(() -> ctx.getRecordedResult("pay"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recorded a failure");
        assertThat(ctx.getRecord("pay").failed()).isTrue();
        assertThat(ctx.getRecord("pay").failureType()).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void replayOrExecuteRethrowsARecordedFailure() {
        DurabilityContext ctx = DurabilityContext.create();
        ctx.restore(CheckpointRecord.failure("pay", "com.example.Declined", "card closed"));
        ctx.setReplayMode(true);
        AtomicInteger callCount = new AtomicInteger(0);

        assertThatThrownBy(() -> ctx.replayOrExecute("pay", () -> {
            callCount.incrementAndGet();
            return "paid";
        })).isInstanceOf(CheckpointReplayException.class)
                .hasMessageContaining("com.example.Declined: card closed");
        assertThat(callCount.get()).isEqualTo(0);
    }

    @Test
    void aThrowingSupplierRecordsNothing() {
        DurabilityContext ctx = DurabilityContext.create();
        assertThatThrownBy(() -> ctx.replayOrExecute("pay", () -> {
            throw new IllegalStateException("down");
        })).hasMessage("down");
        assertThat(ctx.getCheckpointIds()).isEmpty();
    }
}
