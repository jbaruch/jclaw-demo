package jclaw

import ai.koog.agents.cli.transport.CliEvent
import kotlinx.coroutines.flow.toList
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import jclaw.domain.DeclineCritique
import jclaw.domain.DeclineDeployment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe

class TypedCodexTest : StringSpec({
    val serializer = serializer<DeclineCritique>()
    val valid = """{"tier":"THIN","approved":false,"feedback":"Try another approach."}"""

    "subscription transport removes API billing credentials and preserves OAuth login" {
        val pen = Files.createTempDirectory("jclaw-auth-test-")
        try {
            val events = SubscriptionCliTransport.execute(
                command = listOf("/usr/bin/printenv", "CLAUDE_CODE_OAUTH_TOKEN"),
                workspace = pen.toString(),
                env = mapOf(
                    "ANTHROPIC_API_KEY" to "test-api-key",
                    "OPENAI_API_KEY" to "test-api-key",
                    "CLAUDE_CODE_OAUTH_TOKEN" to "test-subscription-token",
                ),
                timeout = 10.seconds,
            ).toList()
            events.filterIsInstance<CliEvent.Stdout>().map { it.content } shouldBe listOf("test-subscription-token")
            listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY").forEach { name ->
                val removed = SubscriptionCliTransport.execute(
                    command = listOf("/usr/bin/printenv", name),
                    workspace = pen.toString(),
                    env = mapOf(name to "test-api-key"),
                    timeout = 10.seconds,
                ).toList()
                removed.filterIsInstance<CliEvent.Stdout>() shouldBe emptyList()
                removed.filterIsInstance<CliEvent.Exit>().single().code shouldBe 1
            }
        } finally {
            Files.delete(pen)
        }
    }

    "the CLI schema is a closed object with every field required" {
        val schema = TypedCodex.schema(serializer<DeclineDeployment>())
        schema["type"] shouldBe JsonPrimitive("object")
        schema["additionalProperties"] shouldBe JsonPrimitive(false)
        schema.getValue("required").jsonArray.map { (it as JsonPrimitive).content }.toSet() shouldBe
            schema.getValue("properties").jsonObject.keys
        val nullableEvent = schema.getValue("properties").jsonObject.getValue("fakeCalendarEventId").jsonObject
        nullableEvent["type"] shouldBe JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))
    }

    "successful final answer becomes a typed critique" {
        val critique = TypedCodex.extract(serializer, success(valid))
        critique.approved shouldBe false
        critique.feedback shouldBe "Try another approach."
    }

    "unknown fields missing fields and invalid enum values fail" {
        val invalid = listOf(
            """{"tier":"THIN","approved":false,"feedback":"x","extra":1}""",
            """{"tier":"THIN","feedback":"x"}""",
            """{"tier":"MAYBE","approved":false,"feedback":"x"}""",
            """{"tier":"THIN","approved":"false","feedback":"x"}""",
        )
        invalid.forEach { text -> shouldThrowAny { TypedCodex.extract(serializer, success(text)) } }
    }

    "a field required by the CLI schema cannot disappear into a Kotlin default" {
        val missingNullable = """{"flavor":"DEADLINE","messageToOrganizer":"Draft","hallwayScript":"Script"}"""
        shouldThrowAny { TypedCodex.extract(serializer<DeclineDeployment>(), success(missingNullable)) }
    }

    "an earlier valid draft cannot hide a malformed final answer" {
        val events = listOf(started, message(valid), message("not JSON"), completed, CliEvent.Exit(0))
        shouldThrowAny { TypedCodex.extract(serializer, events) }
    }

    "a failed turn cannot reuse a prior valid draft" {
        val failure = CliEvent.Stdout("""{"type":"turn.failed","error":{"message":"model unavailable"}}""")
        shouldThrowAny { TypedCodex.extract(serializer, listOf(started, message(valid), failure, CliEvent.Exit(0))) }
    }

    "an error item after an answer cannot be ignored" {
        val failure = CliEvent.Stdout("""{"type":"item.completed","item":{"type":"error","message":"failed"}}""")
        shouldThrowAny { TypedCodex.extract(serializer, listOf(started, message(valid), failure, completed, CliEvent.Exit(0))) }
    }

    "a new turn without an answer cannot reuse the previous turn" {
        val events = listOf(started, message(valid), completed, started, completed, CliEvent.Exit(0))
        shouldThrowAny { TypedCodex.extract(serializer, events) }
    }

    "pre-turn CLI notices do not replace a successful final turn" {
        val notice = CliEvent.Stdout("""{"type":"item.completed","item":{"type":"error","message":"experimental feature"}}""")
        TypedCodex.extract(serializer, listOf(notice) + success(valid)).approved shouldBe false
    }

    "transport failure or nonzero exit cannot become approval" {
        shouldThrowAny { TypedCodex.extract(serializer, success(valid) + CliEvent.Failed("timeout")) }
        shouldThrowAny { TypedCodex.extract(serializer, listOf(started, message(valid), completed, CliEvent.Exit(1))) }
        shouldThrowAny { TypedCodex.extract(serializer, listOf(started, message(valid), completed)) }
        shouldThrowAny { TypedCodex.extract(serializer, listOf(started, message(valid), CliEvent.Exit(0))) }
    }

    "tool output is never accepted as the final answer" {
        val toolOutput = CliEvent.Stdout(buildJsonObject {
            put("type", "item.completed")
            put("item", buildJsonObject {
                put("type", "command_execution")
                put("text", valid)
            })
        }.toString())
        shouldThrowAny { TypedCodex.extract(serializer, listOf(started, toolOutput, completed, CliEvent.Exit(0))) }
    }

})

private fun success(text: String) = listOf(started, message(text), completed, CliEvent.Exit(0))

private fun message(text: String) = CliEvent.Stdout(buildJsonObject {
    put("type", "item.completed")
    put("item", buildJsonObject {
        put("type", "agent_message")
        put("text", text)
    })
}.toString())

private val started = CliEvent.Stdout("""{"type":"turn.started"}""")

private val completed = CliEvent.Stdout("""{"type":"turn.completed","usage":{}}""")
