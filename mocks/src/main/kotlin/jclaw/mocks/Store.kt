package jclaw.mocks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CalendarEvent(
    val id: String,
    val title: String,
    val start: String,
    val organizer: String,
    val declined: Boolean = false,
)

/**
 * Canned state, shared by both mock servers. Three sessions from the same
 * organizer are already declined on the calendar. A calendar records THAT you
 * bailed, never why; the story told each time lives in the agent's memory
 * (memory/documents/), and that gap is the whole of round 3.
 */
object Store {
    val json: Json = Json { prettyPrint = true; encodeDefaults = true }

    val calendar: MutableList<CalendarEvent> = mutableListOf(
        CalendarEvent(
            id = "basic-ai-proficiency-2026",
            title = "Basic AI Proficiency Training (Mandatory)",
            start = "2026-09-08T15:00:00+02:00",
            organizer = "Dana from People Ops",
        ),
        CalendarEvent(
            id = "compliance-refresher-2026",
            title = "Annual Compliance Refresher",
            start = "2026-06-11T10:00:00+02:00",
            organizer = "Dana from People Ops",
            declined = true,
        ),
        CalendarEvent(
            id = "ways-of-working-2026",
            title = "Agile Ways of Working Alignment Workshop",
            start = "2026-04-02T13:00:00+02:00",
            organizer = "Dana from People Ops",
            declined = true,
        ),
        CalendarEvent(
            id = "security-awareness-m4-2026",
            title = "Security Awareness Module 4: Phishing",
            start = "2026-02-19T09:00:00+02:00",
            organizer = "Dana from People Ops",
            declined = true,
        ),
    )

    /** Sensitivity of the person who receives the decline. */
    fun sensitivity(name: String): String = when {
        name.contains("People Ops", ignoreCase = true) -> "TOUCHY"
        name.contains("Dana", ignoreCase = true) -> "TOUCHY"
        else -> "NORMAL"
    }

    private var seq = 0
    fun nextEventId(): String = "staged-${++seq}"
}
