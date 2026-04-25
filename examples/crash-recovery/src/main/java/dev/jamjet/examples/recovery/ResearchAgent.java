package dev.jamjet.examples.recovery;

import dev.jamjet.runtime.instrument.DurabilityContext;
import dev.jamjet.runtime.instrument.annotations.DurableAgent;

/**
 * A simulated research agent with three sequential steps.
 *
 * <p>Each method uses {@link DurabilityContext#replayOrExecute} directly, so it works
 * without the ByteBuddy agent. The agent therefore demonstrates the explicit API that
 * the instrumentation layer automates.</p>
 *
 * <p>Annotated with {@link DurableAgent} to mark intent; the annotation is informational
 * here since we are not running with the JVM agent attached.</p>
 */
@DurableAgent
public class ResearchAgent {

    /**
     * Searches for sources on the given topic.
     * Checkpoint ID: "search"
     */
    public String search(String topic) {
        DurabilityContext ctx = DurabilityContext.current();
        boolean isReplay = ctx.isReplayMode() && ctx.getRecordedResult("search") != null;

        long t0 = System.currentTimeMillis();
        String result = ctx.replayOrExecute("search", () -> {
            sleepMs(500);
            return "Found 3 papers on: " + topic;
        });
        long elapsed = System.currentTimeMillis() - t0;

        if (isReplay) {
            System.out.printf("[1/3] %-52s  skipped (0ms)%n",
                    "Replaying search from checkpoint...");
        } else {
            System.out.printf("[1/3] %-52s  checkpointed (%dms)%n",
                    "Executing search for \"" + topic + "\"...", elapsed);
        }
        return result;
    }

    /**
     * Analyzes the provided sources.
     * Checkpoint ID: "analyze"
     */
    public String analyze(String sources) {
        DurabilityContext ctx = DurabilityContext.current();
        boolean isReplay = ctx.isReplayMode() && ctx.getRecordedResult("analyze") != null;

        long t0 = System.currentTimeMillis();
        String result = ctx.replayOrExecute("analyze", () -> {
            sleepMs(500);
            return "Analysis complete. Key finding: quantum advantage confirmed.";
        });
        long elapsed = System.currentTimeMillis() - t0;

        if (isReplay) {
            System.out.printf("[2/3] %-52s  skipped (0ms)%n",
                    "Replaying analyze from checkpoint...");
        } else {
            System.out.printf("[2/3] %-52s  checkpointed (%dms)%n",
                    "Executing analyze...", elapsed);
        }
        return result;
    }

    /**
     * Synthesizes the analysis into a final report.
     * Checkpoint ID: "synthesize"
     *
     * <p>This step was never checkpointed in phase 1, so it always executes fresh.</p>
     */
    public String synthesize(String analysis) {
        DurabilityContext ctx = DurabilityContext.current();

        long t0 = System.currentTimeMillis();
        String result = ctx.replayOrExecute("synthesize", () -> {
            sleepMs(500);
            return "Final report: " + analysis;
        });
        long elapsed = System.currentTimeMillis() - t0;

        System.out.printf("[3/3] %-52s  completed (%dms)%n",
                "Executing synthesize...", elapsed);
        return result;
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
