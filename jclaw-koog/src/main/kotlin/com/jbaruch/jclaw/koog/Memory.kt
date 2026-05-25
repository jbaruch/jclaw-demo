package com.jbaruch.jclaw.koog

/**
 * Pre-seeded prior-decline history — matches the canned calendar in calendar-mcp's fixture.
 *
 * In the talk's Round 3, this is what makes the agent "remember" prior excuses and avoid
 * reusing them. For the demo we keep it in-process (no sqlite); the agent's `searchPriorExcuses`
 * tool reads from this list.
 *
 * If you want real persistent memory: install Koog's ChatMemory feature and back it with a
 * JDBC ChatHistoryProvider against a sqlite file. The seed entries would then be written
 * by a separate `mocks/seed-memory` module.
 */
object SeedMemory {
    val priorDeclines: List<PriorDecline> = listOf(
        PriorDecline(
            eventName = "JNation 2025 Speaker Dinner",
            organizerName = "Filipe Correia",
            flavorUsed = ExcuseFlavor.EMERGENCY_MEETING,
            date = "2025-06-19",
        ),
        PriorDecline(
            eventName = "Devoxx BE 2025 Speaker Dinner",
            organizerName = "Stephan Janssen",
            flavorUsed = ExcuseFlavor.FAMILY_OBLIGATION,
            date = "2025-10-09",
        ),
        PriorDecline(
            eventName = "Spring I/O 2026 Speaker Dinner",
            organizerName = "Sergi Almar",
            flavorUsed = ExcuseFlavor.HOTEL_ISSUE,
            date = "2026-04-15",
        ),
    )
}
