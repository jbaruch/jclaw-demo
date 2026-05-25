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
import java.util.UUID

/**
 * Mock calendar MCP server — Koog-backed, stdio transport.
 *
 * Tools:
 *   getCalendar() -> List<CalendarEvent>
 *   createCalendarEvent(title, startIso, durationMin) -> String (eventId)
 *
 * Canned payload per SPEC.md §2.1.
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

@LLMDescription("Tools for reading the user's calendar and creating new events")
class CalendarTools : ToolSet {

    @Tool
    @LLMDescription("Returns the user's calendar events, including past declined dinners")
    fun getCalendar(): List<CalendarEvent> = cannedCalendar

    @Tool
    @LLMDescription("Creates a calendar event and returns the new event id")
    fun createCalendarEvent(
        @LLMDescription("Event title — visible to anyone who reads the user's calendar")
        title: String,
        @LLMDescription("ISO-8601 start time with offset, e.g. 2026-05-26T20:00:00+01:00")
        startIso: String,
        @LLMDescription("Duration in minutes")
        durationMin: Int,
    ): String {
        val id = "evt-${UUID.randomUUID().toString().take(8)}"
        System.err.println("[calendar-mcp] createCalendarEvent(title=\"$title\", start=$startIso, durationMin=$durationMin) -> $id")
        return id
    }

    private val cannedCalendar: List<CalendarEvent> = listOf(
        CalendarEvent("jnation-2026-dinner",   "JNation Speaker Dinner",   "2026-05-26T20:00:00+01:00", "Filipe Correia"),
        CalendarEvent("jnation-2025-dinner",   "JNation Speaker Dinner",   "2025-06-19T20:00:00+01:00", "Filipe Correia",  declined = true, declineReason = "EMERGENCY_MEETING"),
        CalendarEvent("devoxx-2025-dinner",    "Devoxx BE Speaker Dinner", "2025-10-09T20:00:00+02:00", "Stephan Janssen", declined = true, declineReason = "FAMILY_OBLIGATION"),
        CalendarEvent("spring-io-2026-dinner", "Spring I/O Speaker Dinner","2026-04-15T20:00:00+02:00", "Sergi Almar",     declined = true, declineReason = "HOTEL_ISSUE"),
    )
}

fun main() {
    runBlocking {
        val registry = ToolRegistry { tools(CalendarTools().asTools()) }
        System.err.println("[calendar-mcp] starting stdio MCP server with ${CalendarTools::class.simpleName}")
        startStdioMcpServer(registry)
        // startStdioMcpServer returns after setup; keep JVM alive so the server keeps processing stdin.
        awaitCancellation()
    }
}
