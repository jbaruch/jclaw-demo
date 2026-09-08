package jclaw

import ai.koog.agents.features.opentelemetry.attribute.CustomAttribute
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryConfig
import ai.koog.agents.features.opentelemetry.integration.langfuse.addLangfuseSpanAdapter
import ai.koog.agents.features.opentelemetry.integration.otlp.OtlpJsonSpanExporter
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.export.SpanExporter
import io.opentelemetry.kotlin.tracing.export.SpanProcessor
import io.opentelemetry.kotlin.tracing.data.SpanData
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
 * subgraph and node, Gemini model calls with their messages and reported token
 * counts, and tools invoked through Koog. Subscription CLI stages are node spans
 * with typed input/output and provider metadata; they do not report token prices.
 * Human confirmation, delivery and memory writes happen outside the agent trace.
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
    private var exporter: Shipments? = null

    /**
     * Counts exports in flight, so flush() can wait for the last one to land. The batch
     * processor's own forceFlush() gives up after five seconds, and a batch that carries
     * whole prompts can take longer than that to ship; it keeps shipping in the
     * background, and the process must not exit until it is done.
     */
    private class Shipments(private val delegate: SpanExporter) : SpanExporter {
        val inFlight = AtomicInteger(0)
        val lastActivity = AtomicLong(System.nanoTime())
        val spansShipped = AtomicInteger(0)

        override suspend fun export(telemetry: List<SpanData>): OperationResultCode {
            inFlight.incrementAndGet(); lastActivity.set(System.nanoTime())
            try {
                return delegate.export(telemetry.map(::withLangfuseNodeDetails)).also { if (it == OperationResultCode.Success) spansShipped.addAndGet(telemetry.size) }
            } finally {
                inFlight.decrementAndGet(); lastActivity.set(System.nanoTime())
            }
        }

        override suspend fun forceFlush(): OperationResultCode = delegate.forceFlush()
        override suspend fun shutdown(): OperationResultCode = delegate.shutdown()
    }

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
        val shipments = Shipments(
            OtlpJsonSpanExporter(
                endpoint = "$host/api/public/otel/v1/traces",
                headers = mapOf("Authorization" to "Basic $auth"),
                timeout = 10.seconds,
            )
        ).also { exporter = it }
        addSpanProcessor { batchSpanProcessor(shipments).also { processor = it } }
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
        val shipments = exporter ?: return
        processor?.forceFlush()
        // forceFlush returns when the queue is handed to the exporter, or after its own
        // five-second cap. Either way, wait for the exports themselves to finish: nothing
        // in flight, and nothing started for half a second.
        withTimeoutOrNull(30_000) {
            while (shipments.inFlight.get() > 0 || System.nanoTime() - shipments.lastActivity.get() < 500_000_000L) delay(100)
        }
    }

    /** How many spans reached Langfuse so far, for a closing trace line. */
    val spansShipped: Int get() = exporter?.spansShipped?.get() ?: 0

    private fun env(name: String): String = requireNotNull(System.getenv(name)) { "$name is not set" }
}

/** Keep Koog's trace tree and timings while exposing typed handoffs in Langfuse's I/O tabs. */
@OptIn(ExperimentalApi::class)
internal fun withLangfuseNodeDetails(span: SpanData): SpanData {
    val node = span.attributes["koog.node.id"] as? String ?: return span
    val extra = buildMap<String, Any> {
        (span.attributes["koog.node.input"] as? String)?.let { put("langfuse.observation.input", it) }
        (span.attributes["koog.node.output"] as? String)?.let { put("langfuse.observation.output", it) }
        val provider = when (node) {
            "deploy", "refine" -> "anthropic"
            "verify" -> "openai"
            else -> null
        }
        if (provider != null) {
            put("langfuse.observation.metadata.provider", provider)
            put("langfuse.observation.metadata.client", if (node == "verify") "codex" else "claude-code")
            put("langfuse.observation.metadata.authentication", "subscription")
            put("langfuse.observation.metadata.role", when (node) {
                "deploy" -> "drafter"
                "refine" -> "refiner"
                else -> "judge"
            })
        }
        if (node == "verify") {
            // This uses the same helper as execution, not a reconstructed paraphrase.
            // CLI-added instructions and private reasoning are not part of this field.
            val attempt = (span.attributes["koog.node.input"] as? String)?.let {
                runCatching { Json.decodeFromString<ReviewAttempt>(it) }.getOrNull()
            }
            attempt?.let {
                put("langfuse.observation.metadata.application_prompt", CliCritic.codexPrompt(it.plan))
                put("langfuse.observation.metadata.review_attempt", it.refinements + 1)
            }
        }
    }
    return object : SpanData by span {
        override val attributes: Map<String, Any> = span.attributes + extra
    }
}
