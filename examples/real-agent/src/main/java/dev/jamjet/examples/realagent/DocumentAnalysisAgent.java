package dev.jamjet.examples.realagent;

import dev.jamjet.runtime.instrument.DurabilityContext;
import dev.jamjet.runtime.instrument.annotations.DurableAgent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Three-step document analysis agent with full durability checkpointing.
 *
 * <p>Each step calls {@link DurabilityContext#replayOrExecute} directly (no ByteBuddy agent
 * required). The {@link DurableAgent} annotation is informational and marks intent.</p>
 *
 * <p>Steps:</p>
 * <ol>
 *   <li>{@link #fetchDocument} — HTTP GET, truncated to 8,000 chars, checkpointed as "fetch"</li>
 *   <li>{@link #analyze} — GPT-4o-mini structured analysis, checkpointed as "analyze"</li>
 *   <li>{@link #summarize} — GPT-4o-mini structured summary, checkpointed as "summarize"</li>
 * </ol>
 *
 * <p>If the process crashes after step 2 and is restarted with a pre-populated context,
 * steps 1 and 2 are skipped (replayed from checkpoint) and only step 3 executes.</p>
 */
@DurableAgent("document-analysis")
public class DocumentAnalysisAgent {

    private static final int MAX_CONTENT_CHARS = 8_000;

    private final OpenAiClient llm;
    private final HttpClient http;

    public DocumentAnalysisAgent(OpenAiClient llm) {
        this.llm = llm;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches the document at {@code url} and returns its text content (truncated to 8,000 chars).
     * Checkpoint ID: {@code fetch}
     */
    public String fetchDocument(String url) {
        DurabilityContext ctx = DurabilityContext.current();
        long t0 = System.currentTimeMillis();

        String content = ctx.replayOrExecute("fetch", () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "JamJet-DocumentAnalysisAgent/1.0")
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException("HTTP " + response.statusCode() + " fetching " + url);
                }

                String raw = response.body();
                // Strip HTML tags for a cleaner text representation
                String text = raw.replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s{2,}", " ")
                        .trim();

                return text.length() > MAX_CONTENT_CHARS
                        ? text.substring(0, MAX_CONTENT_CHARS) + "... [truncated]"
                        : text;
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch document: " + e.getMessage(), e);
            }
        });

        long elapsed = System.currentTimeMillis() - t0;
        boolean fromCheckpoint = elapsed < 50;  // replayed from cache if nearly instant
        int kb = content.length() / 1024;
        System.out.printf("%-12s %-44s %s%n",
                "[fetch]",
                "Fetching document...",
                fromCheckpoint
                        ? String.format("✓ ~%dkB (replayed from checkpoint)", kb)
                        : String.format("✓ ~%dkB retrieved, checkpointed", kb));

        return content;
    }

    /**
     * Analyzes the document content with GPT-4o-mini and returns a structured result.
     * Checkpoint ID: {@code analyze}
     */
    public AnalysisResult analyze(String content) {
        DurabilityContext ctx = DurabilityContext.current();
        long t0 = System.currentTimeMillis();

        AnalysisResult result = ctx.replayOrExecute("analyze", () -> {
            String systemPrompt = """
                    You are a document analysis assistant. Analyze the provided document and return a JSON object with:
                    - key_points: array of 3-5 most important points (strings)
                    - topics: array of main topics covered (strings)
                    - sentiment: overall tone (one of: positive, neutral, negative, technical, informational)
                    """;
            String userMessage = "Analyze this document:\n\n" + content;
            try {
                return llm.chatStructured(systemPrompt, userMessage, AnalysisResult.class);
            } catch (Exception e) {
                throw new RuntimeException("Analysis failed: " + e.getMessage(), e);
            }
        });

        long elapsed = System.currentTimeMillis() - t0;
        boolean fromCheckpoint = elapsed < 50;
        System.out.printf("%-12s %-44s %s%n",
                "[analyze]",
                "Analyzing with GPT-4o-mini...",
                fromCheckpoint
                        ? "✓ (replayed from checkpoint)"
                        : String.format("✓ %.1fs, checkpointed", elapsed / 1000.0));

        return result;
    }

    /**
     * Generates a structured summary from the analysis result.
     * Checkpoint ID: {@code summarize}
     */
    public Summary summarize(AnalysisResult analysis) {
        DurabilityContext ctx = DurabilityContext.current();
        long t0 = System.currentTimeMillis();

        Summary summary = ctx.replayOrExecute("summarize", () -> {
            String systemPrompt = """
                    You are a document summarization assistant. Given an analysis result, produce a final summary as a JSON object with:
                    - title: a concise descriptive title for the document (string)
                    - key_points: the most important takeaways (array of strings, max 5)
                    - topics: main subject areas (array of strings)
                    - sentiment: overall tone (string)
                    - word_count: estimated word count of the original document (integer)
                    """;
            String userMessage = String.format(
                    "Produce a final summary based on this analysis:\nKey points: %s\nTopics: %s\nSentiment: %s",
                    analysis.keyPoints(), analysis.topics(), analysis.sentiment());
            try {
                return llm.chatStructured(systemPrompt, userMessage, Summary.class);
            } catch (Exception e) {
                throw new RuntimeException("Summarization failed: " + e.getMessage(), e);
            }
        });

        long elapsed = System.currentTimeMillis() - t0;
        boolean fromCheckpoint = elapsed < 50;
        System.out.printf("%-12s %-44s %s%n",
                "[summarize]",
                "Generating structured summary...",
                fromCheckpoint
                        ? "✓ (replayed from checkpoint)"
                        : String.format("✓ %.1fs, checkpointed", elapsed / 1000.0));

        return summary;
    }
}
