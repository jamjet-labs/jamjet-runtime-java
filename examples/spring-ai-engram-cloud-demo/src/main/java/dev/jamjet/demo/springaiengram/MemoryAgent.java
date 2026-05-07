package dev.jamjet.demo.springaiengram;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class MemoryAgent {

    private final ChatClient chatClient;

    public MemoryAgent(ChatClient.Builder chatClientBuilder, MemoryTools memoryTools) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        You are a helpful assistant with durable memory backed by Engram.
                        When the user shares a fact about themselves, call rememberFact to store it.
                        When the user asks a question that may have been answered before, call recallFacts first.
                        If recallFacts returns nothing relevant, say "I don't know yet" — do not guess.
                        Always pass the session id (provided in the user message header [session=...]) as the userId argument to both tools.
                        """)
                .defaultTools(memoryTools)
                .build();
    }

    public String chat(String sessionId, String userMessage) {
        return chatClient.prompt()
                .user("[session=" + sessionId + "] " + userMessage)
                .call()
                .content();
    }
}
