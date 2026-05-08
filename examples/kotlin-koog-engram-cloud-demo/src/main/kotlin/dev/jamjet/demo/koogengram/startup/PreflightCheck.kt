package dev.jamjet.demo.koogengram.startup

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Validates required env vars and waits for Engram before the Spring app accepts traffic.
 *
 * Mirrors Track 1's `PreflightCheck` so both demos fail fast with the same UX
 * when keys are missing or Engram isn't reachable.
 */
@Component
@Profile("!test")
class PreflightCheck(
    @Value("\${app.engram.health-url:http://127.0.0.1:9090/health}")
    private val engramHealthUrl: String,
    private val env: Map<String, String> = System.getenv(),
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        validateEnv()
        waitForEngram(Duration.ofSeconds(30))
    }

    fun validateEnv() {
        require(!env["OPENAI_API_KEY"].isNullOrBlank()) {
            "OPENAI_API_KEY is not set. Get a key at https://platform.openai.com/api-keys " +
                "and put it in your .env file."
        }
        require(!env["JAMJET_API_KEY"].isNullOrBlank()) {
            "JAMJET_API_KEY is not set. Sign up at https://cloud.jamjet.dev, create a project, " +
                "and put the API key in your .env file."
        }
    }

    fun waitForEngram(timeout: Duration) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(engramHealthUrl))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()

        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            try {
                val resp = client.send(request, HttpResponse.BodyHandlers.discarding())
                if (resp.statusCode() == 200) return
            } catch (_: Exception) {
                // retry until deadline
            }
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while waiting for Engram", e)
            }
        }

        throw IllegalStateException(
            "Engram is not reachable at $engramHealthUrl. Run `docker compose up -d` first.",
        )
    }
}
