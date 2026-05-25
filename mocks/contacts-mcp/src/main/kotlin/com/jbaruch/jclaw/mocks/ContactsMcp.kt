package com.jbaruch.jclaw.mocks

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.mcp.server.startStdioMcpServer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Mock contacts MCP server — Koog-backed, stdio transport.
 *
 * Models Baruch's personal-CRM notes: who is touchy, who is easygoing, and how to
 * reach them. Used by j-claw to tune both the excuse flavor and the message tone.
 *
 * Tools:
 *   getContactSensitivity(name) -> Sensitivity
 *   sendDecline(eventId, message) -> DeclineReceipt
 */

@Serializable
enum class Sensitivity { EASYGOING, NORMAL, TOUCHY }

@Serializable
data class DeclineReceipt(val delivered: Boolean, val deliveredAt: String)

@LLMDescription("Personal-CRM notes about Baruch's contacts: sensitivity profiles and the ability to send messages")
class ContactsTools : ToolSet {

    @Tool
    @LLMDescription("Returns Baruch's note on how sensitive the named contact is to last-minute excuses — EASYGOING / NORMAL / TOUCHY")
    fun getContactSensitivity(
        @LLMDescription("Full name of the contact, e.g. \"Roberto Cortez\"")
        name: String,
    ): Sensitivity {
        val out = sensitivities[name] ?: Sensitivity.NORMAL
        System.err.println("[contacts-mcp] getContactSensitivity(\"$name\") -> $out")
        return out
    }

    @Tool
    @LLMDescription("Sends a decline message to the organizer of the named event")
    fun sendDecline(
        @LLMDescription("Calendar event id (from conference-mcp's getCalendar)")
        eventId: String,
        @LLMDescription("The decline message text the organizer will see")
        message: String,
    ): DeclineReceipt {
        val now = Instant.now().toString()
        val preview = if (message.length > 80) message.take(80) + "…" else message
        System.err.println("[contacts-mcp] sendDecline(eventId=\"$eventId\") delivered at $now — \"$preview\"")
        return DeclineReceipt(delivered = true, deliveredAt = now)
    }

    private val sensitivities: Map<String, Sensitivity> = mapOf(
        "Roberto Cortez"  to Sensitivity.EASYGOING,
        "Stephan Janssen" to Sensitivity.NORMAL,
        "Sergi Almar"     to Sensitivity.NORMAL,
    )
}

fun main() {
    runBlocking {
        val registry = ToolRegistry { tools(ContactsTools().asTools()) }
        System.err.println("[contacts-mcp] starting stdio MCP server with ${ContactsTools::class.simpleName}")
        startStdioMcpServer(registry)
        awaitCancellation()
    }
}
