package dev.jamjet.examples.recovery;

import dev.jamjet.runtime.instrument.DurabilityContext;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 2: Loads previously saved checkpoint state into a fresh {@link DurabilityContext},
 * enables replay mode, and re-runs all three ResearchAgent steps.
 *
 * <p>Steps 1 (search) and 2 (analyze) return instantly from the checkpoint cache.
 * Step 3 (synthesize) was never checkpointed, so it executes fresh (~500ms).</p>
 *
 * <p>Run with: {@code mvn exec:java -Drecover}</p>
 */
public class RecoveryRunner {

    public static void main(String[] args) {
        printHeader();

        // Load saved state from Phase 1
        LinkedHashMap<String, String> savedState;
        try {
            savedState = loadState(CrashSimulator.STATE_FILE);
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Could not load state file '" + CrashSimulator.STATE_FILE
                    + "' — run CrashSimulator first.");
            System.err.println("Details: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Restore checkpoints into a new DurabilityContext
        DurabilityContext ctx = DurabilityContext.create();
        for (Map.Entry<String, String> entry : savedState.entrySet()) {
            ctx.recordResult(entry.getKey(), entry.getValue());
        }
        ctx.setReplayMode(true);
        DurabilityContext.setCurrent(ctx);

        long wallStart = System.currentTimeMillis();

        try {
            ResearchAgent agent = new ResearchAgent();

            // Steps 1 and 2 replay from checkpoints (0ms each)
            String sources = agent.search("quantum computing");
            String analysis = agent.analyze(sources);

            // Step 3 executes fresh (~500ms)
            String report = agent.synthesize(analysis);

            long totalMs = System.currentTimeMillis() - wallStart;
            int replayedCount = savedState.size();
            int freshCount = ctx.getCheckpointIds().size() - replayedCount;

            System.out.println();
            System.out.println("Recovery complete.");
            System.out.printf("  Replayed:     %d checkpoints (saved ~%dms)%n",
                    replayedCount, replayedCount * 500);
            System.out.printf("  Re-executed:  %d step%n", freshCount);
            System.out.printf("  Total time:   %dms (vs ~%dms without recovery)%n",
                    totalMs, ctx.getCheckpointIds().size() * 500);

        } finally {
            DurabilityContext.clear();
        }
    }

    /**
     * Deserializes the checkpoint map written by {@link CrashSimulator#saveState}.
     */
    @SuppressWarnings("unchecked")
    static LinkedHashMap<String, String> loadState(String filePath)
            throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filePath))) {
            return (LinkedHashMap<String, String>) ois.readObject();
        }
    }

    private static void printHeader() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  Phase 2: Recovery — resuming from last checkpoint       ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
