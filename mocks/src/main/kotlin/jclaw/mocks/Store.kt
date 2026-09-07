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
 * What j-claw actually SAID, per decline. A calendar records that you bailed;
 * it does not record the story you told. That gap is the whole of round 3.
 */
@Serializable
data class PriorDecline(
    val event: String,
    val organizer: String,
    val flavor: String,
    val whatWeSaid: String,
)

/**
 * Canned state, shared by both mock servers. Three burned excuses are already
 * on the record - that is what makes round 3 (memory) do visible work.
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

    /** Pre-seeded so round 3 works on the first run, with no warm-up. */
    val priorDeclines: List<PriorDecline> = listOf(
        PriorDecline(
            event = "Annual Compliance Refresher",
            organizer = "Dana from People Ops",
            flavor = "CALENDAR_CONFLICT",
            whatWeSaid = "Told Dana it collided with a customer call that could not move.",
        ),
        PriorDecline(
            event = "Agile Ways of Working Alignment Workshop",
            organizer = "Dana from People Ops",
            flavor = "FAMILY_OBLIGATION",
            whatWeSaid = "Told Dana it was a family obligation and promised to catch the recording.",
        ),
        PriorDecline(
            event = "Security Awareness Module 4: Phishing",
            organizer = "Dana from People Ops",
            flavor = "CUSTOMER_ESCALATION",
            whatWeSaid = "Told Dana a customer escalation had just landed on my desk.",
        ),
    )

    private var seq = 0
    fun nextEventId(): String = "staged-${++seq}"
}
