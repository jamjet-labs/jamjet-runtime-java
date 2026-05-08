package dev.jamjet.demo.koogengram.cloud

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryConfig
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.HexFormat

private const val DEFAULT_JAMJET_URL = "https://api.jamjet.dev"
private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * Configure an OpenTelemetry span exporter that ships agent traces to
 * [JamJet Cloud](https://cloud.jamjet.dev) via direct OTLP/JSON intake.
 *
 * Mirrors the DataDog / Langfuse pattern that ships with Koog
 * (`addDatadogExporter`, `addLangfuseExporter` in
 * [ai.koog.agents.features.opentelemetry.integration]): a thin extension on
 * [OpenTelemetryConfig] that registers an OTLP-shaped [SpanExporter] pointed at
 * JamJet's `/v1/otlp/v1/traces` endpoint with `Authorization: Bearer <api-key>`.
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

    addSpanExporter(JamjetOtlpJsonSpanExporter("$apiUrl/v1/otlp/v1/traces", key, timeout))
}

/**
 * Custom OTLP/JSON [SpanExporter] for JamJet Cloud's intake endpoint.
 *
 * The Java OTel SDK 1.37 ships only an OTLP/protobuf HTTP exporter, so we marshal
 * spans to OTLP/JSON ourselves. The wire format follows the OTLP spec
 * (https://opentelemetry.io/docs/specs/otlp/#json-protobuf-encoding):
 * camelCase field names, hex-encoded `traceId` / `spanId`, int64 fields encoded
 * as strings to preserve precision in JS clients.
 */
internal class JamjetOtlpJsonSpanExporter(
    private val endpoint: String,
    apiKey: String,
    timeout: Duration,
) : SpanExporter {

    private val authHeader: String = "Bearer $apiKey"
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()
    private val requestTimeout: Duration = timeout
    // OTLP/JSON spec: omit absent fields; we already build maps with only non-null values.
    private val mapper: ObjectMapper = jacksonObjectMapper()

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        if (spans.isEmpty()) return CompletableResultCode.ofSuccess()

        val payload = OtlpJsonMarshaler.toExportRequest(spans)
        val body = mapper.writeValueAsBytes(payload)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Authorization", authHeader)
            .header("User-Agent", "jamjet-koog-otlp-exporter/1.0")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val result = CompletableResultCode()
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, error ->
                when {
                    error != null -> {
                        log.warn("OTLP/JSON export to {} failed: {}", endpoint, error.toString())
                        result.fail()
                    }
                    response.statusCode() in 200..299 -> result.succeed()
                    else -> {
                        log.warn(
                            "OTLP/JSON export to {} failed: HTTP {} body={}",
                            endpoint, response.statusCode(),
                            response.body().take(MAX_LOG_BODY_CHARS),
                        )
                        result.fail()
                    }
                }
            }
        return result
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    private companion object {
        private val log = LoggerFactory.getLogger(JamjetOtlpJsonSpanExporter::class.java)
        private const val MAX_LOG_BODY_CHARS = 500
    }
}

/**
 * Pure functions that marshal Java OTel `SpanData` into the OTLP/JSON wire shape.
 * Kept separate from the [SpanExporter] so it's easy to unit-test without HTTP.
 */
internal object OtlpJsonMarshaler {

    private val HEX = HexFormat.of()

    fun toExportRequest(spans: Collection<SpanData>): Map<String, Any> {
        // Group by Resource — each unique resource becomes one resourceSpans entry.
        val byResource = spans.groupBy { it.resource }
        val resourceSpans = byResource.map { (resource, resourceSpans) ->
            // Then group by InstrumentationScope.
            val byScope = resourceSpans.groupBy { it.instrumentationScopeInfo }
            mapOf(
                "resource" to mapOf(
                    "attributes" to attributesToJson(resource.attributes.asMap()),
                ),
                "scopeSpans" to byScope.map { (scope, scopeSpans) ->
                    mapOf(
                        "scope" to mutableMapOf<String, Any>("name" to scope.name).apply {
                            scope.version?.let { put("version", it) }
                        },
                        "spans" to scopeSpans.map(::spanToJson),
                    )
                },
            )
        }
        return mapOf("resourceSpans" to resourceSpans)
    }

    private fun spanToJson(span: SpanData): Map<String, Any?> = buildMap {
        put("traceId", span.traceId)
        put("spanId", span.spanId)
        if (span.parentSpanContext.isValid) put("parentSpanId", span.parentSpanId)
        put("name", span.name)
        put("kind", span.kind.ordinal + 1)  // OTLP SpanKind: 1=INTERNAL, 2=SERVER, 3=CLIENT, ...
        put("startTimeUnixNano", span.startEpochNanos.toString())
        put("endTimeUnixNano", span.endEpochNanos.toString())
        put("attributes", attributesToJson(span.attributes.asMap()))
        if (span.events.isNotEmpty()) {
            put("events", span.events.map { ev ->
                mapOf(
                    "timeUnixNano" to ev.epochNanos.toString(),
                    "name" to ev.name,
                    "attributes" to attributesToJson(ev.attributes.asMap()),
                )
            })
        }
        if (span.links.isNotEmpty()) {
            put("links", span.links.map { link ->
                mapOf(
                    "traceId" to link.spanContext.traceId,
                    "spanId" to link.spanContext.spanId,
                    "attributes" to attributesToJson(link.attributes.asMap()),
                )
            })
        }
        put(
            "status",
            mutableMapOf<String, Any>("code" to span.status.statusCode.ordinal).apply {
                span.status.description.takeIf { it.isNotEmpty() }?.let { put("message", it) }
            },
        )
    }

    private fun attributesToJson(attrs: Map<io.opentelemetry.api.common.AttributeKey<*>, Any>): List<Map<String, Any>> =
        attrs.map { (key, value) ->
            mapOf("key" to key.key, "value" to anyValue(value))
        }

    private fun anyValue(value: Any?): Map<String, Any> = when (value) {
        null -> mapOf("stringValue" to "")
        is String -> mapOf("stringValue" to value)
        is Boolean -> mapOf("boolValue" to value)
        is Long -> mapOf("intValue" to value.toString())
        is Int -> mapOf("intValue" to value.toString())
        is Double -> mapOf("doubleValue" to value)
        is Float -> mapOf("doubleValue" to value.toDouble())
        is ByteArray -> mapOf("stringValue" to HEX.formatHex(value))
        is List<*> -> mapOf("arrayValue" to mapOf("values" to value.map { anyValue(it) }))
        else -> mapOf("stringValue" to value.toString())
    }
}
