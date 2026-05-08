package dev.jamjet.demo.springaiengram;

import dev.jamjet.engram.EngramClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MemoryTools {

    private static final String DEMO_ORG = "demo";

    private final EngramClient engram;

    public MemoryTools(EngramClient engram) {
        this.engram = engram;
    }

    @Tool(description = "Store a fact about the user in durable memory. Use when the user shares preferences or personal details.")
    public String rememberFact(
            @ToolParam(description = "The fact to remember, in the user's own words") String text,
            @ToolParam(description = "The user identifier — pass through the session id from the chat request") String userId) {
        var messages = List.of(Map.<String, String>of("role", "user", "content", text));
        var result = engram.add(messages, userId, DEMO_ORG, userId);
        return "Stored fact for user " + userId + ": " + result;
    }

    @Tool(description = "Recall facts previously stored about the user. Returns the top matches; use when answering a question that may depend on prior context.")
    public List<Map<String, Object>> recallFacts(
            @ToolParam(description = "Natural-language query describing what you're looking for") String query,
            @ToolParam(description = "The user identifier — pass through the session id from the chat request") String userId) {
        return engram.recall(query, userId, DEMO_ORG, 5);
    }
}
