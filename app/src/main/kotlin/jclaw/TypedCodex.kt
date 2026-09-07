package jclaw

import ai.koog.agents.cli.CliAIAgent
import ai.koog.agents.cli.transport.CliEvent
import ai.koog.agents.cli.transport.CliTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.KSerializer
import java.nio.file.Files

/**
 * Typed output from Codex, which Koog does not support.
 *
 * `agents-cli` ships four `claude(...)` overloads and only two `codex(...)` ones -
 * the typed pair exists for Claude and not for Codex. So a pipeline that hands
 * typed data between stages can use Claude and cannot use Codex.
 *
 * That is a gap, not a wall. `CliAIAgent.builder(transport)` is the seam: give it a
 * binary, the flags to run it, how to turn input into a prompt, and how to read the
 * output back. Roughly thirty lines and Codex becomes a typed stage like any other.
 *
 * Worth doing on stage precisely because it is unglamorous: a framework ten days old
 * has holes, and the interesting question is whether it left you somewhere to stand.
 */
object TypedCodex {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Coding agents read their working directory. Give them an empty one. */
    private val pen: String = Files.createTempDirectory("jclaw-codex")
        .toFile().apply { deleteOnExit() }.absolutePath

    fun <I : Any, O : Any> agent(
        serializer: KSerializer<O>,
        systemPrompt: String,
        request: (I) -> String,
    ): CliAIAgent<I, O> =
        CliAIAgent.builder(CliTransport.default())
            .custom<I, O>()
            .binaryPath("codex")
            .name("codex-typed")
            .workspace(pen)
            .systemPrompt(systemPrompt)
            .flags { _, _ ->
                listOf(
                    "exec",                    // non-interactive
                    "--json",                  // one JSON event per line on stdout
                    "--skip-git-repo-check",   // the workspace is a temp dir, not a repo
                )
            }
            // Here is the actual difference between "supported" and "not supported".
            //
            // Koog's claude() typed overload passes `--json-schema` and the CLI enforces
            // it. Codex has no such flag, so the first attempt came back confidently
            // shaped as {"status":"DRAFT_ONLY","reason":...} - valid JSON, invented
            // schema, useless. The model was never told what shape to produce.
            //
            // So describe the shape in the prompt. One flag over there, one paragraph
            // here. Codex also ignores system prompts, so that gets folded in too.
            .generateRequest { input: I ->
                buildString {
                    appendLine(systemPrompt)
                    appendLine()
                    appendLine(request(input))
                    appendLine()
                    appendLine("Return JSON with EXACTLY these fields and nothing else:")
                    appendLine(describe(serializer.descriptor))
                    append("Reply with ONLY that JSON object. No prose, no markdown fence.")
                }
            }
            .extractOutput { events, logger ->
                events.filterIsInstance<CliEvent.Failed>().firstOrNull()?.let {
                    error("codex failed: ${it.message}")
                }

                // --json gives one event per line; the model's text arrives in
                // item.completed events. Take the last one that parses as our type.
                val texts = events.filterIsInstance<CliEvent.Stdout>()
                    .mapNotNull { runCatching { json.parseToJsonElement(it.content).jsonObject }.getOrNull() }
                    .filter { it["type"]?.jsonPrimitive?.content == "item.completed" }
                    .mapNotNull { it["item"]?.jsonObject?.get("text")?.jsonPrimitive?.content }

                texts.asReversed().firstNotNullOfOrNull { text ->
                    runCatching { json.decodeFromString(serializer, text.trimFence()) }.getOrNull()
                } ?: error(
                    "codex returned nothing parseable as ${serializer.descriptor.serialName}. " +
                        "Last text was: ${texts.lastOrNull()?.take(200)}"
                ).also { logger.warn { "codex typed extraction failed" } }
            }
            .build()

    /**
     * A compact shape description from the serializer, so the prompt and the type
     * cannot drift apart. Add a field to the data class and the prompt updates itself.
     */
    private fun describe(d: kotlinx.serialization.descriptors.SerialDescriptor): String =
        (0 until d.elementsCount).joinToString("\n") { i ->
            val e = d.getElementDescriptor(i)
            val type = when {
                e.kind == kotlinx.serialization.descriptors.SerialKind.ENUM ->
                    "one of [" + (0 until e.elementsCount).joinToString(", ") { e.getElementName(it) } + "]"
                e.serialName.startsWith("kotlin.collections.List") -> "array"
                e.serialName == "kotlin.String" -> "string"
                e.serialName == "kotlin.Boolean" -> "boolean"
                else -> e.serialName.substringAfterLast('.')
            }
            val nullable = if (e.isNullable) ", or null" else ""
            "  \"${d.getElementName(i)}\": $type$nullable"
        }

    /** Models fence JSON even when told not to. Strip it rather than fail. */
    private fun String.trimFence(): String = trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```")
        .trim()
}
