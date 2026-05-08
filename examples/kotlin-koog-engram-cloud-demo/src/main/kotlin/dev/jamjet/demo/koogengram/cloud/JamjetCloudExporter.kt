package dev.jamjet.demo.koogengram.cloud

import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryConfig
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import java.time.Duration

private const val DEFAULT_JAMJET_URL = "https://api.jamjet.dev"
private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * Configure an OpenTelemetry span exporter that ships agent traces to
 * [JamJet Cloud](https://cloud.jamjet.dev) via OTLP/HTTP-protobuf intake.
 *
 * Mirrors the DataDog / Langfuse pattern that ships with Koog
 * (`addDatadogExporter`, `addLangfuseExporter` in
 * `ai.koog.agents.features.opentelemetry.integration`): a thin extension on
 * [OpenTelemetryConfig] that registers the standard OTel HTTP span exporter
 * pointed at JamJet's `/v1/otlp/v1/traces` endpoint with
 * `Authorization: Bearer <api-key>`.
 *
 * Uses the stock `OtlpHttpSpanExporter` (already on the classpath via
 * `agents-features-opentelemetry-jvm:0.8.0` → `opentelemetry-exporter-otlp:1.49`)
 * so this file is wiring only — no custom marshaler, no HTTP client.
 *
 * Registered via [addSpanExporter][OpenTelemetryConfig.addSpanExporter], which
 * wraps the exporter in a batch span processor — the cloud HTTP round-trip
 * happens on a worker thread instead of blocking the agent on each span end.
 *
 * Typical usage:
 *
 * ```kotlin
 * AIAgent(
 *     promptExecutor = openAIExecutor,
 *     llmModel = OpenAIModels.Chat.GPT4oMini,
 *     toolRegistry = ToolRegistry { tools(MyTools) },
 * ) {
 *     install(OpenTelemetry) {
 *         setServiceInfo(serviceName = "my-koog-agent", serviceVersion = "1.0")
 *         addJamjetCloudExporter()
 *     }
 * }
 * ```
 *
 * @param apiKey JamJet Cloud project API key. If `null`, falls back to the
 *        `JAMJET_API_KEY` environment variable.
 * @param apiUrl JamJet Cloud base URL. Defaults to `https://api.jamjet.dev`.
 *        Override for self-hosted deployments or local testing.
 * @param timeout request timeout (default 10 s).
 *
 * @see <a href="https://cloud.jamjet.dev">JamJet Cloud</a>
 * @see <a href="https://docs.jamjet.dev/observability/otlp">JamJet OTLP intake</a>
 */
@JvmOverloads
public fun OpenTelemetryConfig.addJamjetCloudExporter(
    apiKey: String? = null,
    apiUrl: String = DEFAULT_JAMJET_URL,
    timeout: Duration = DEFAULT_TIMEOUT,
) {
    val key = apiKey
        ?: System.getenv("JAMJET_API_KEY")
        ?: error(
            "JAMJET_API_KEY is missing. Pass it explicitly to addJamjetCloudExporter() " +
                "or set the JAMJET_API_KEY environment variable. " +
                "Sign up at https://cloud.jamjet.dev to create a project key."
        )

    addSpanExporter(
        OtlpHttpSpanExporter.builder()
            .setEndpoint("$apiUrl/v1/otlp/v1/traces")
            .addHeader("Authorization", "Bearer $key")
            .setTimeout(timeout)
            .build()
    )
}
