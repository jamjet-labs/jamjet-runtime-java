package com.example.durable;

import dev.jamjet.runtime.instrument.DurabilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs with the real {@code -javaagent} attached (failsafe argLine) against a
 * {@code @DurableAgent} outside the instrument package. No ByteBuddy test helpers here:
 * the classes are transformed by {@code JamjetAgent.premain} at load time, the way an
 * application sees them.
 */
class JavaagentCheckpointIT {

    @AfterEach
    void clear() {
        DurabilityContext.clear();
    }

    @Test
    void theAgentIsAttached() {
        assertThat(ManagementFactory.getRuntimeMXBean().getInputArguments())
                .anySatisfy(arg -> assertThat(arg).startsWith("-javaagent:")
                        .contains("jamjet-runtime-instrument"));
    }

    @Test
    void aCheckpointOutsideTheInstrumentPackageRunsAndReplays() {
        DurabilityContext recording = DurabilityContext.create();
        DurabilityContext.setCurrent(recording);
        PaymentAgent first = new PaymentAgent();
        String receipt = first.charge("alice", 500);
        assertThat(receipt).isEqualTo("receipt-alice-500-1");
        assertThat(first.executions).containsExactly("charge:alice:500");

        DurabilityContext replay = replayOf(recording);
        DurabilityContext.setCurrent(replay);
        PaymentAgent second = new PaymentAgent();
        assertThat(second.charge("alice", 500)).isEqualTo("receipt-alice-500-1");
        assertThat(second.executions).isEmpty();
    }

    @Test
    void replayIsKeyedByArguments() {
        DurabilityContext recording = DurabilityContext.create();
        DurabilityContext.setCurrent(recording);
        new PaymentAgent().charge("alice", 500);

        DurabilityContext replay = replayOf(recording);
        DurabilityContext.setCurrent(replay);
        PaymentAgent agent = new PaymentAgent();
        // bob was never charged: his call must run, not return alice's receipt
        assertThat(agent.charge("bob", 500)).isEqualTo("receipt-bob-500-1");
        assertThat(agent.executions).containsExactly("charge:bob:500");
    }

    @Test
    void repeatedCallsReplayInOrder() {
        DurabilityContext recording = DurabilityContext.create();
        DurabilityContext.setCurrent(recording);
        PaymentAgent first = new PaymentAgent();
        int a = first.nextTicket();
        int b = first.nextTicket();
        first.notifyCustomer("alice");
        assertThat(a).isEqualTo(1);
        assertThat(b).isEqualTo(2);

        DurabilityContext replay = replayOf(recording);
        DurabilityContext.setCurrent(replay);
        PaymentAgent second = new PaymentAgent();
        assertThat(second.nextTicket()).isEqualTo(1);
        assertThat(second.nextTicket()).isEqualTo(2);
        second.notifyCustomer("alice");
        assertThat(second.executions).isEmpty();
        // a third call is past the recorded frontier and runs
        assertThat(second.nextTicket()).isEqualTo(1);
        assertThat(second.executions).containsExactly("nextTicket");
    }

    @Test
    void aFailedCallReplaysAsTheSameFailure() {
        DurabilityContext recording = DurabilityContext.create();
        DurabilityContext.setCurrent(recording);
        PaymentAgent first = new PaymentAgent();
        assertThatThrownBy(() -> first.refund("alice"))
                .isInstanceOf(PaymentAgent.RefundDeclined.class)
                .hasMessage("card closed for alice");

        DurabilityContext replay = replayOf(recording);
        DurabilityContext.setCurrent(replay);
        PaymentAgent second = new PaymentAgent();
        assertThatThrownBy(() -> second.refund("alice"))
                .isInstanceOf(PaymentAgent.RefundDeclined.class)
                .hasMessage("card closed for alice");
        assertThat(second.executions).isEmpty();
    }

    /** A fresh context holding everything {@code source} recorded, in replay mode. */
    private static DurabilityContext replayOf(DurabilityContext source) {
        DurabilityContext replay = DurabilityContext.create();
        source.getRecords().forEach(replay::restore);
        replay.setReplayMode(true);
        return replay;
    }
}
