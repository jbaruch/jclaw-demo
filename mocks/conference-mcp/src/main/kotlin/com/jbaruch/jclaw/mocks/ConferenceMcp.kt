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

/**
 * Mock conference MCP server — Koog-backed, stdio transport. Read-only.
 *
 * Tools:
 *   getCalendar() -> List<CalendarEvent>
 *   getEventAttendees(eventId) -> List<String>   (who else will be at this event)
 *
 * Canned payload per SPEC.md §2.1. Attendees are real conference speakers so the
 * agent can avoid excuses that contradict known attendees (e.g., "I'm in another city"
 * when the recipient knows you were just at lunch with another attendee).
 */

@Serializable
data class CalendarEvent(
    val id: String,
    val title: String,
    val start: String,
    val organizer: String,
    val declined: Boolean = false,
    val declineReason: String? = null,
)

@LLMDescription("Tools for reading the user's conference calendar and the attendee list per event")
class ConferenceTools : ToolSet {

    @Tool
    @LLMDescription("Returns the user's calendar events, including past declined dinners")
    fun getCalendar(): List<CalendarEvent> = cannedCalendar

    @Tool
    @LLMDescription("Returns the speaker attendees expected at an event — useful for picking an excuse that doesn't contradict who else will be there")
    fun getEventAttendees(
        @LLMDescription("Event id from getCalendar")
        eventId: String,
    ): List<String> = cannedAttendees[eventId] ?: emptyList()

    private val cannedCalendar: List<CalendarEvent> = listOf(
        CalendarEvent("jnation-2026-dinner",   "JNation Speaker Dinner",   "2026-05-26T20:00:00+01:00", "Filipe Correia"),
        CalendarEvent("jnation-2025-dinner",   "JNation Speaker Dinner",   "2025-06-19T20:00:00+01:00", "Filipe Correia",  declined = true, declineReason = "EMERGENCY_MEETING"),
        CalendarEvent("devoxx-2025-dinner",    "Devoxx BE Speaker Dinner", "2025-10-09T20:00:00+02:00", "Stephan Janssen", declined = true, declineReason = "FAMILY_OBLIGATION"),
        CalendarEvent("spring-io-2026-dinner", "Spring I/O Speaker Dinner","2026-04-15T20:00:00+02:00", "Sergi Almar",     declined = true, declineReason = "HOTEL_ISSUE"),
    )

    // Real conference speakers — chosen so the agent has plausible names to reason about.
    private val cannedAttendees: Map<String, List<String>> = mapOf(
        "jnation-2026-dinner" to listOf(
            "Viktor Gamov", "Venkat Subramaniam", "Holly Cummins", "Sebastian Daschner",
            "Trisha Gee", "Marit van Dijk",
        ),
        "jnation-2025-dinner" to listOf(
            "Viktor Gamov", "Yara Senger", "Otavio Santana",
        ),
        "devoxx-2025-dinner" to listOf(
            "Mala Gupta", "Jose Paumard", "Brian Vermeer",
        ),
        "spring-io-2026-dinner" to listOf(
            "Josh Long", "Jürgen Höller", "Sebastien Deleuze",
        ),
    )
}

fun main() {
    runBlocking {
        val registry = ToolRegistry { tools(ConferenceTools().asTools()) }
        System.err.println("[conference-mcp] starting stdio MCP server with ${ConferenceTools::class.simpleName}")
        startStdioMcpServer(registry)
        awaitCancellation()
    }
}
