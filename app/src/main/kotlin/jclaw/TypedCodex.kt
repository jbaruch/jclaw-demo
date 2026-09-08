package jclaw

import ai.koog.agents.cli.CliAIAgent
import ai.koog.agents.cli.transport.CliEvent
import ai.koog.prompt.executor.clients.openai.base.structure.OpenAIStandardJsonSchemaGenerator
import ai.koog.prompt.structure.json.JsonStructure
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * Koog has typed Claude CLI overloads, but only untyped Codex overloads.
 * Codex itself DOES support a schema: this adapter connects Koog's serializer to
 * `codex exec --output-schema`, then parses the final successful turn as O.
 */
object TypedCodex {
    private val json = Json // Strict JSON: unknown fields and malformed values fail.

    fun <I : Any, O : Any> agent(
        serializer: KSerializer<O>,
        systemPrompt: String,
        request: (I) -> String,
    ): CliAIAgent<I, O> {
        val pen = cliWorkspace("codex")
        val schemaFile = Files.createTempFile(pen, "response-", ".schema.json")
            .toFile().apply { deleteOnExit() }
        schemaFile.writeText(schema(serializer).toString())

        return CliAIAgent.builder(SubscriptionCliTransport)
            .custom<I, O>()
            .binaryPath("codex")
            .name("codex-typed-judge")
            .workspace(pen.toString())
            .timeout(3.minutes)
            .flags { _, _ ->
                listOf(
                    "exec", "--json", "--skip-git-repo-check", "--ephemeral",
                    "--ignore-user-config", "--ignore-rules",
                    "--sandbox", "read-only",
                    "-c", "approval_policy=\"never\"",
                    "-c", "forced_login_method=\"chatgpt\"",
                    "-c", "project_doc_max_bytes=0",
                    "-c", "web_search=\"disabled\"",
                    "--enable", "skip_host_skill_discovery",
                    "--output-schema", schemaFile.absolutePath,
                ) + disabledFeatures.flatMap { listOf("--disable", it) }
            }
            .generateRequest { input: I ->
                // Custom builders don't map system messages to Codex flags.
                "$systemPrompt\n\n${request(input)}"
            }
            .extractOutput { events, _ -> extract(serializer, events) }
            .build()
    }

    internal fun <O> schema(serializer: KSerializer<O>): JsonObject =
        JsonStructure.create(
            serializer = serializer,
            json = json,
            schemaGenerator = OpenAIStandardJsonSchemaGenerator,
        ).schema.schema

    /** Never recover an earlier parseable message after a failed final answer. */
    internal fun <O> extract(serializer: KSerializer<O>, events: List<CliEvent>): O {
        events.filterIsInstance<CliEvent.Failed>().firstOrNull()?.let {
            error("Codex failed: ${it.message}")
        }
        val exit = events.filterIsInstance<CliEvent.Exit>().lastOrNull()
        check(exit?.code == 0) { "Codex did not exit successfully (exit ${exit?.code})." }

        val output = events.filterIsInstance<CliEvent.Stdout>()
            .filter { it.content.isNotBlank() }
            .map { json.parseToJsonElement(it.content).jsonObject }
        val turnStart = output.indexOfLast { it.type() == "turn.started" }
        check(turnStart >= 0) { "Codex returned no started turn." }
        val turn = output.drop(turnStart + 1)
        // Startup notices can use item.type=error before turn.started. Once a turn
        // starts, an error must not be hidden by an earlier parseable answer.
        turn.firstOrNull {
            it.type() == "turn.failed" || it.type() == "error" ||
                (it["item"] as? JsonObject)?.type() == "error"
        }?.let {
            error("Codex reported a failed turn: ${it.toString().take(300)}")
        }
        check(turn.lastOrNull()?.type() == "turn.completed") {
            "Codex returned no completed final turn."
        }
        val finalText = turn.asSequence()
            .filter { it.type() == "item.completed" }
            .mapNotNull { it["item"] as? JsonObject }
            .filter { it.type() == "agent_message" }
            .lastOrNull()
            ?.get("text")?.jsonPrimitive?.content
            ?: error("Codex completed without a final agent message.")

        val value = json.parseToJsonElement(finalText)
        validateShape(serializer.descriptor, value)
        return json.decodeFromJsonElement(serializer, value)
    }

    // kotlinx.serialization accepts quoted booleans and numbers even in its default
    // mode. Check JSON types and required fields before decoding into a decision.
    private fun validateShape(descriptor: SerialDescriptor, value: JsonElement) {
        if (value == JsonNull) {
            require(descriptor.isNullable) { "Unexpected null for ${descriptor.serialName}." }
            return
        }
        when (val kind = descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                require(value is JsonObject) { "Expected object for ${descriptor.serialName}." }
                val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName)
                require(value.keys == fields.toSet()) { "Fields do not match ${descriptor.serialName}." }
                fields.forEachIndexed { index, field ->
                    validateShape(descriptor.getElementDescriptor(index), value.getValue(field))
                }
            }
            StructureKind.LIST -> {
                require(value is JsonArray) { "Expected array for ${descriptor.serialName}." }
                value.forEach { validateShape(descriptor.getElementDescriptor(0), it) }
            }
            is PrimitiveKind, SerialKind.ENUM -> {
                require(value is JsonPrimitive) { "Expected primitive for ${descriptor.serialName}." }
                val stringType = kind == PrimitiveKind.STRING || kind == PrimitiveKind.CHAR || kind == SerialKind.ENUM
                require(value.isString == stringType) { "Wrong JSON type for ${descriptor.serialName}." }
            }
            else -> error("Unsupported typed CLI output: ${descriptor.serialName} ($kind).")
        }
    }

    private fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.content

    // The critic only evaluates supplied text. Disable the installed CLI's tool
    // providers as well as ignoring user config, which can contain live MCP servers.
    private val disabledFeatures = listOf(
        "shell_tool", "unified_exec", "apps", "plugins", "remote_plugin", "hooks",
        "multi_agent", "multi_agent_v2", "browser_use", "browser_use_external",
        "computer_use", "image_generation", "code_mode", "code_mode_host",
        "skill_search", "memories", "view_image", "goals", "sleep_tool",
    )
}
