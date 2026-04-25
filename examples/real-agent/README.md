# JamJet Real Agent: Document Analysis

A working agent that fetches a URL, analyzes the content with GPT-4o-mini, and produces a structured summary — with every step checkpointed for crash recovery.

## Prerequisites

- **Java 21+**
- **Maven 3.9+**
- **OpenAI API key** with access to `gpt-4o-mini`
- Parent modules installed locally: `cd ../.. && mvn install -DskipTests`

## How to Run

```bash
# 1. Install parent modules (first time only)
cd ../..
mvn install -DskipTests
cd examples/real-agent

# 2. Set your API key
export OPENAI_API_KEY=sk-...

# 3. Run with default URL (Oracle virtual threads article)
mvn exec:java

# 4. Or pass a custom URL
mvn exec:java -Dexec.args="https://example.com/your-article"
```

## Expected Output

```
╔══════════════════════════════════════════════════════════╗
║  JamJet Real Agent: Document Analysis                   ║
╚══════════════════════════════════════════════════════════╝

URL: https://blogs.oracle.com/javamagazine/post/java-virtual-threads

[fetch]      Fetching document...                   ✓ ~12kB retrieved, checkpointed
[analyze]    Analyzing with GPT-4o-mini...          ✓ 2.3s, checkpointed
[summarize]  Generating structured summary...       ✓ 1.8s, checkpointed

=== Document Analysis ===
Title: Java Virtual Threads: Scaling Concurrency Without Complexity
Key Points:
  1. Virtual threads are lightweight JVM threads that don't map 1:1 to OS threads
  2. They enable high-throughput server applications without reactive programming
  3. Existing blocking APIs work transparently with virtual threads
Topics: [Java, virtual threads, concurrency, JVM, Project Loom]
Sentiment: informational
Word Count: 3,200

Total: 4.2s | Checkpoints: 3 | Recoverable: yes
```

## Try Crashing It

Simulate crash recovery by adding a `System.exit(1)` after step 2 in `DocumentAnalysisAgent.java`:

```java
public AnalysisResult analyze(String content) {
    // ... existing code ...
    System.exit(1); // crash after analyze
    return result;
}
```

Then implement state persistence (see `crash-recovery` example for the pattern), restart, and watch steps 1 and 2 replay instantly from checkpoints while only step 3 runs fresh.

## Make It Yours

**Swap the LLM** — replace `OpenAiClient` with any HTTP-based model:

```java
// Use Ollama locally (no API key needed)
// Point OpenAiClient at http://localhost:11434/v1/chat/completions
// with model "llama3.2" or "mistral"
```

**Add a checkpoint** — add a fourth step (e.g., translation):

```java
public String translate(Summary summary, String targetLanguage) {
    return DurabilityContext.current().replayOrExecute("translate", () -> {
        return llm.chat("Translate to " + targetLanguage, summary.title());
    });
}
```

**Use a different document** — any publicly accessible URL works. For best results, use articles, blog posts, or documentation pages (not login-protected content).

**Use Ollama instead of OpenAI** — run models locally, no API key:

```bash
# Start Ollama
ollama serve
ollama pull llama3.2

# Then modify OpenAiClient to use http://localhost:11434/v1 as base URL
```
