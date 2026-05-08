package dev.jamjet.demo.koogengram

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.jamjet.engram.EngramClient
import org.springframework.stereotype.Component

/**
 * Koog tools backed by Engram durable memory.
 *
 * Implements [ToolSet] so the agent can register every `@Tool`-annotated
 * function in one go via `tools(memoryTools)` inside a `ToolRegistry { ... }`.
 *
 * The autoconfigured [EngramClient] (provided by `engram-spring-boot-starter`)
 * is bound to `engram.base-url`. Both `rememberFact` and `recallFacts` scope
 * memory by `userId` so multiple chat sessions stay isolated.
 */
@Component
class MemoryTools(private val engram: EngramClient) : ToolSet {

    // Engram returns List<Map<String, Object>>; Koog's kotlinx-serialization-based tool layer
    // can't serialize a Map<String, Any> back to a string for the LLM, so we hand it through
    // Jackson — gives the LLM a clean JSON string to read.
    private val mapper: ObjectMapper = jacksonObjectMapper()

    @Tool
    @LLMDescription(
        "Store a fact about the user in durable memory. " +
            "Use when the user shares preferences or personal details."
    )
    suspend fun rememberFact(
        @LLMDescription("The fact to remember, in the user's own words")
        text: String,
        @LLMDescription("The user identifier — pass through the session id from the chat request")
        userId: String,
    ): String {
        val messages = listOf(mapOf("role" to "user", "content" to text))
        val result = engram.add(messages, userId, DEMO_ORG, userId)
        return "Stored fact for user $userId: $result"
    }

    @Tool
    @LLMDescription(
        "Recall facts previously stored about the user. Returns the top matches; " +
            "use when answering a question that may depend on prior context."
    )
    suspend fun recallFacts(
        @LLMDescription("Natural-language query describing what you're looking for")
        query: String,
        @LLMDescription("The user identifier — pass through the session id from the chat request")
        userId: String,
    ): String {
        val facts = engram.recall(query, userId, DEMO_ORG, 5)
        return if (facts.isEmpty()) "[]" else mapper.writeValueAsString(facts)
    }

    private companion object {
        const val DEMO_ORG = "demo"
    }
}
