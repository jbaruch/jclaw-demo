package com.jbaruch.jclaw.mocks

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.mcp.server.startStdioMcpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Mock organizer MCP server — Koog-backed, stdio transport.
 *
 * Tools:
 *   getOrganizerSensitivity(name) -> Sensitivity
 *   sendDecline(eventId, message) -> DeclineReceipt
 *
 * Canned per SPEC.md §2.2.
 */

@Serializable
enum class Sensitivity { EASYGOING, NORMAL, TOUCHY }

@Serializable
data class DeclineReceipt(val delivered: Boolean, val deliveredAt: String)

@LLMDescription("Tools for sending decline messages to event organizers")
class OrganizerTools : ToolSet {

    @Tool
    @LLMDescription("Returns how sensitive the named organizer is to last-minute excuses")
    fun getOrganizerSensitivity(
        @LLMDescription("Full name of the organizer, e.g. \"Filipe Correia\"")
        name: String,
    ): Sensitivity {
        val out = sensitivities[name] ?: Sensitivity.NORMAL
        System.err.println("[organizer-mcp] getOrganizerSensitivity(\"$name\") -> $out")
        return out
    }

    @Tool
    @LLMDescription("Sends a decline message to the organizer of the named event")
    fun sendDecline(
        @LLMDescription("Calendar event id (from calendar-mcp's getCalendar)")
        eventId: String,
        @LLMDescription("The decline message text the organizer will see")
        message: String,
    ): DeclineReceipt {
        val now = Instant.now().toString()
        val preview = if (message.length > 80) message.take(80) + "…" else message
        System.err.println("[organizer-mcp] sendDecline(eventId=\"$eventId\") delivered at $now — \"$preview\"")
        return DeclineReceipt(delivered = true, deliveredAt = now)
    }

    private val sensitivities: Map<String, Sensitivity> = mapOf(
        "Filipe Correia" to Sensitivity.EASYGOING,
    )
}

fun main() = runBlocking {
    val registry = ToolRegistry { tools(OrganizerTools().asTools()) }
    System.err.println("[organizer-mcp] starting stdio MCP server with ${OrganizerTools::class.simpleName}")
    startStdioMcpServer(registry)
}
