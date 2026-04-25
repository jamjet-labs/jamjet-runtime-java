package dev.jamjet.examples.realagent;

import dev.jamjet.runtime.instrument.DurabilityContext;

/**
 * Entry point for the Document Analysis demo.
 *
 * <p>Reads {@code OPENAI_API_KEY} from the environment, optionally takes a URL as the
 * first command-line argument (defaults to the Oracle virtual threads article), then runs
 * the three-step {@link DocumentAnalysisAgent} pipeline with full durability checkpointing.</p>
 *
 * <p>Run with:</p>
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   mvn exec:java
 *   # or with a custom URL:
 *   mvn exec:java -Dexec.args="https://example.com/article"
 * </pre>
 */
public class DocumentAnalysisDemo {

    private static final String DEFAULT_URL =
            "https://blogs.oracle.com/javamagazine/post/java-virtual-threads";

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: OPENAI_API_KEY environment variable is not set.");
            System.err.println("Export it and re-run:");
            System.err.println("  export OPENAI_API_KEY=sk-...");
            System.exit(1);
        }

        String url = args.length > 0 ? args[0] : DEFAULT_URL;

        printHeader();
        System.out.println("URL: " + url);
        System.out.println();

        DurabilityContext ctx = DurabilityContext.create();
        DurabilityContext.setCurrent(ctx);

        long totalStart = System.currentTimeMillis();

        try {
            OpenAiClient llm = new OpenAiClient(apiKey);
            DocumentAnalysisAgent agent = new DocumentAnalysisAgent(llm);

            String content = agent.fetchDocument(url);
            AnalysisResult analysis = agent.analyze(content);
            Summary summary = agent.summarize(analysis);

            long totalElapsed = System.currentTimeMillis() - totalStart;
            int checkpointCount = ctx.getCheckpointIds().size();

            System.out.println();
            printResults(summary);
            System.out.println();
            System.out.printf("Total: %.1fs | Checkpoints: %d | Recoverable: yes%n",
                    totalElapsed / 1000.0, checkpointCount);

        } catch (Exception e) {
            System.err.println("\nError: " + e.getMessage());
            if (System.getenv("DEBUG") != null) {
                e.printStackTrace();
            }
            System.exit(1);
        } finally {
            DurabilityContext.clear();
        }
    }

    private static void printHeader() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  JamJet Real Agent: Document Analysis                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printResults(Summary summary) {
        System.out.println("=== Document Analysis ===");
        System.out.println("Title: " + (summary.title() != null ? summary.title() : "(none)"));
        System.out.println("Key Points:");
        if (summary.keyPoints() != null) {
            for (int i = 0; i < summary.keyPoints().size(); i++) {
                System.out.printf("  %d. %s%n", i + 1, summary.keyPoints().get(i));
            }
        }
        String topics = summary.topics() != null ? String.join(", ", summary.topics()) : "";
        System.out.println("Topics: [" + topics + "]");
        System.out.println("Sentiment: " + (summary.sentiment() != null ? summary.sentiment() : "(none)"));

        String wordCountStr = summary.wordCount() > 0
                ? String.format("%,d", summary.wordCount())
                : "unknown";
        System.out.println("Word Count: " + wordCountStr);
    }
}
