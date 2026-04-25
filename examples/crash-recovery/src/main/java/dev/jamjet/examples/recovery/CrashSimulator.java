package dev.jamjet.examples.recovery;

import dev.jamjet.runtime.instrument.DurabilityContext;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 1: Executes the first two steps of the ResearchAgent workflow, checkpoints
 * them to disk via Java serialization, then simulates a crash with {@link System#exit(1)}.
 *
 * <p>State is written to {@code recovery-demo-state.dat} in the current directory.
 * {@link RecoveryRunner} picks this file up and resumes from step 3.</p>
 *
 * <p>Run with: {@code mvn exec:java -Pcrash}</p>
 */
public class CrashSimulator {

    static final String STATE_FILE = "recovery-demo-state.dat";

    public static void main(String[] args) {
        printHeader();

        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);

        try {
            ResearchAgent agent = new ResearchAgent();

            // Step 1: search
            agent.search("quantum computing");

            // Step 2: analyze
            agent.analyze("sources");

            // Save checkpoints to disk before simulated crash
            saveState(ctx);

            System.out.println();
            System.out.println("[CRASH] Process killed after checkpoint 2.");

        } catch (Exception e) {
            System.err.println("Setup error: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        } finally {
            DurabilityContext.clear();
        }

        // Simulate process crash — RecoveryRunner picks up from here
        System.exit(1);
    }

    /**
     * Serializes checkpoint IDs and their string values to a file so they can be
     * restored into a fresh {@link DurabilityContext} in another process.
     */
    static void saveState(DurabilityContext ctx) throws IOException {
        // Collect checkpoint id -> string value pairs in insertion order
        LinkedHashMap<String, String> state = new LinkedHashMap<>();
        for (String id : ctx.getCheckpointIds()) {
            Object value = ctx.getRecordedResult(id);
            state.put(id, value == null ? "" : value.toString());
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(STATE_FILE))) {
            oos.writeObject(state);
        }
    }

    private static void printHeader() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  Phase 1: Normal execution (will crash after step 2)    ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
