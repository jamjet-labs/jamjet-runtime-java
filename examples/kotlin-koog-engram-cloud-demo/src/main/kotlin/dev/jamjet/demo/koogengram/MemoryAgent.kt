package dev.jamjet.demo.koogengram

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.utils.io.use
import dev.jamjet.demo.koogengram.cloud.addJamjetCloudExporter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * A Koog [AIAgent] wired with Engram-backed memory tools and OpenTelemetry
 * traces shipped to JamJet Cloud via OTLP/JSON.
 *
 * The autoconfigured `MultiLLMPromptExecutor` (provided by `koog-spring-boot-starter`)
 * routes to whichever LLM client beans are present — here just OpenAI, configured
 * via `ai.koog.openai.api-key`. The `addJamjetCloudExporter` extension function in
 * [dev.jamjet.demo.koogengram.cloud] is the only piece of observability code in
 * the entire demo — everything else (LLM spans, tool spans, cost) is captured
 * automatically by the OpenTelemetry feature.
 */
@Service
class MemoryAgent(
    private val promptExecutor: MultiLLMPromptExecutor,
    private val memoryTools: MemoryTools,
    @Value("\${jamjet.cloud.api-key}") private val jamjetApiKey: String,
    @Value("\${jamjet.cloud.api-url:https://api.jamjet.dev}") private val jamjetApiUrl: String,
) {

    private val systemPrompt = """
        You are a helpful assistant with durable memory backed by Engram.
        When the user shares a fact about themselves, call rememberFact to store it.
        When the user asks a question that may have been answered before, call recallFacts first.
        If recallFacts returns nothing relevant, say "I don't know yet" — do not guess.
        Always pass the session id (provided in the user message header [session=...]) as the userId argument to both tools.
    """.trimIndent()

    suspend fun chat(sessionId: String, userMessage: String): String {
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = OpenAIModels.Chat.GPT4oMini,
            toolRegistry = ToolRegistry { tools(memoryTools) },
            systemPrompt = systemPrompt,
            strategy = singleRunStrategy(),
            maxIterations = 10,
        ) {
            install(OpenTelemetry) {
                setServiceInfo(
                    serviceName = SERVICE_NAME,
                    serviceVersion = SERVICE_VERSION,
                )
                setVerbose(true)
                addJamjetCloudExporter(apiKey = jamjetApiKey, apiUrl = jamjetApiUrl)
            }
        }

        return agent.use { it.run("[session=$sessionId] $userMessage") }
    }

    private companion object {
        const val SERVICE_NAME = "kotlin-koog-engram-demo"
        const val SERVICE_VERSION = "1.0.0"
    }
}
