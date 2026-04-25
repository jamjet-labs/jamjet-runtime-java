package dev.jamjet.examples.newway;

import dev.jamjet.runtime.instrument.DurabilityContext;
import dev.jamjet.runtime.instrument.annotations.Checkpoint;
import dev.jamjet.runtime.instrument.annotations.DurableAgent;
import org.springframework.stereotype.Service;

/**
 * A durable agent registered as a Spring bean.
 *
 * <p>The {@link DurableAgent} annotation is detected by
 * {@code DurableAgentBeanPostProcessor} at startup and logged. Each method annotated with
 * {@link Checkpoint} documents intent; durability is enforced via
 * {@link DurabilityContext#replayOrExecute} in the method body.</p>
 *
 * <p>Responses here are stubbed — swap in a real LLM client to make it live.</p>
 */
@DurableAgent("research-pipeline")
@Service
public class NewWayAgent {

    /**
     * Searches for relevant sources on the given topic.
     * In production, connect to a search API or vector database here.
     */
    @Checkpoint("search")
    public String search(String topic) {
        return DurabilityContext.current().replayOrExecute("search", () -> {
            // Replace with: searchClient.query(topic)
            simulateWork(200);
            return "Found 5 papers on: " + topic;
        });
    }

    /**
     * Analyzes the provided sources and extracts key insights.
     * In production, call an LLM (OpenAI, Ollama, etc.) here.
     */
    @Checkpoint("analyze")
    public String analyze(String sources) {
        return DurabilityContext.current().replayOrExecute("analyze", () -> {
            // Replace with: llmClient.chat(systemPrompt, sources)
            simulateWork(500);
            return "Analysis complete. Key insight: " + sources.substring(0, Math.min(sources.length(), 30));
        });
    }

    /**
     * Synthesizes the analysis into a final report.
     * In production, call an LLM with a summarization prompt.
     */
    @Checkpoint("synthesize")
    public String synthesize(String analysis) {
        return DurabilityContext.current().replayOrExecute("synthesize", () -> {
            // Replace with: llmClient.chat(summaryPrompt, analysis)
            simulateWork(300);
            return "Report: " + analysis;
        });
    }

    private static void simulateWork(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
