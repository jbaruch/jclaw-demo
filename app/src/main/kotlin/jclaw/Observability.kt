package jclaw

import ai.koog.agents.features.opentelemetry.attribute.CustomAttribute
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryConfig
import ai.koog.agents.features.opentelemetry.integration.langfuse.addLangfuseSpanAdapter
import ai.koog.agents.features.opentelemetry.integration.otlp.OtlpJsonSpanExporter
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.tracing.export.SpanProcessor
import io.opentelemetry.kotlin.tracing.export.batchSpanProcessor
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * Langfuse, when the keys are there.
 *
 * LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY and LANGFUSE_BASE_URL come from .env via
 * ./jclaw. Absent, nothing is installed and the demo never depends on a network
 * service being up. Present, every agent run becomes a trace: the strategy, each
 * subgraph and node, every model call with its messages and token counts, every
 * tool call. Koog's OpenTelemetry feature emits them, with the span attributes
 * Langfuse's Agent Graph needs.
 *
 * The attributes below ride on EVERY span, which is what Langfuse asks of
 * OpenTelemetry instrumentation: one session per ./jclaw process, so the Sessions
 * view groups the turns of one conversation; a user; tags and metadata to filter
 * the dashboard by round and mode; an environment and a release.
 */
@OptIn(ExperimentalApi::class)
object Observability {
    val enabled: Boolean = System.getenv("LANGFUSE_PUBLIC_KEY") != null

    private const val RELEASE = "ideaconf-2026"
    private const val KOOG = "1.2.0"

    /** One process, one session: the turns of one conversation, grouped. */
    private val session: String =
        "jclaw-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    private var processor: SpanProcessor? = null

    fun OpenTelemetryConfig.langfuse(round: Int, vararg tags: String, metadata: Map<String, String> = emptyMap()) {
        setServiceInfo("j-claw", RELEASE)
        // Prompts and completions in the spans. This is a demo, and the audience should
        // see exactly what the model saw.
        setVerbose(true)

        // Koog's addLangfuseExporter() does the next ten lines for us, wrapped in a batch
        // processor we cannot reach. We have to reach it: on this OpenTelemetry SDK,
        // shutdown closes the exporter BEFORE draining the queue, so whatever is still
        // batched when the process ends - the critic, the root of the trace - never
        // arrives. Owning the processor is what lets flush() drain it. The SDK is never
        // shut down on purpose; the process exit does that, after the flush.
        val host = System.getenv("LANGFUSE_HOST") ?: System.getenv("LANGFUSE_BASE_URL") ?: "https://cloud.langfuse.com"
        val auth = Base64.getEncoder().encodeToString("${env("LANGFUSE_PUBLIC_KEY")}:${env("LANGFUSE_SECRET_KEY")}".toByteArray())
        val exporter = OtlpJsonSpanExporter(
            endpoint = "$host/api/public/otel/v1/traces",
            headers = mapOf("Authorization" to "Basic $auth"),
            timeout = 10.seconds,
        )
        addSpanProcessor { batchSpanProcessor(exporter).also { processor = it } }
        addLangfuseSpanAdapter(
            traceAttributes = listOf(
                CustomAttribute("langfuse.trace.name", "jclaw-round$round"),
                CustomAttribute("langfuse.session.id", session),
                CustomAttribute("langfuse.user.id", "baruch"),
                CustomAttribute("langfuse.trace.tags", listOf("round$round") + tags),
                CustomAttribute("langfuse.environment", "demo"),
                CustomAttribute("langfuse.release", RELEASE),
                CustomAttribute("langfuse.trace.metadata.koog", KOOG),
                CustomAttribute("langfuse.trace.metadata.round", round.toString()),
            ) + metadata.map { (k, v) -> CustomAttribute("langfuse.trace.metadata.$k", v) },
        )
    }

    /**
     * Drain whatever is still batched. Call AFTER closing the agent: closing is when Koog
     * ends the spans it still holds open - the approval node, the strategy, the agent
     * invocation that is the root of the trace - and only then are they in the queue.
     */
    suspend fun flush() {
        processor?.forceFlush()
    }

    private fun env(name: String): String = requireNotNull(System.getenv(name)) { "$name is not set" }
}
