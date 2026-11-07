package com.example.durable;

import dev.jamjet.runtime.instrument.annotations.Checkpoint;
import dev.jamjet.runtime.instrument.annotations.DurableAgent;

import java.util.ArrayList;
import java.util.List;

/**
 * A durable agent in a package other than {@code dev.jamjet.runtime.instrument}, so the
 * inlined advice has to reach the runtime through public members only.
 */
@DurableAgent("payments")
public class PaymentAgent {

    /** Every body execution, in order. A replayed call leaves no entry. */
    public final List<String> executions = new ArrayList<>();

    @Checkpoint("charge")
    public String charge(String customer, long cents) {
        executions.add("charge:" + customer + ":" + cents);
        return "receipt-" + customer + "-" + cents + "-" + executions.size();
    }

    @Checkpoint
    public int nextTicket() {
        executions.add("nextTicket");
        return executions.size();
    }

    @Checkpoint
    public void notifyCustomer(String customer) {
        executions.add("notify:" + customer);
    }

    @Checkpoint("refund")
    public String refund(String customer) throws RefundDeclined {
        executions.add("refund:" + customer);
        throw new RefundDeclined("card closed for " + customer);
    }

    public static final class RefundDeclined extends Exception {
        public RefundDeclined(String message) {
            super(message);
        }
    }
}
