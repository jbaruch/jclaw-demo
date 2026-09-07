package jclaw

import jclaw.domain.DeclineDeployment
import jclaw.domain.DeclineRequest
import jclaw.domain.ExcuseFlavor
import jclaw.domain.Scenario
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import kotlin.system.exitProcess

/** Does Codex actually hand back a typed DeclineDeployment? One call, no pipeline. */
fun main(): Unit = runBlocking {
    val deploy = TypedCodex.agent<DeclineRequest, DeclineDeployment>(
        serializer = serializer<DeclineDeployment>(),
        systemPrompt = Scenario.SYSTEM_PROMPT,
        request = { req ->
            "Draft a plan to get out of this. Pick a flavor that is NOT in " +
                "recentlyUsedFlavors. Set fakeCalendarEventId to null if the reason is " +
                "true and needs no staging.\n$req"
        },
    )

    val request = DeclineRequest(
        eventId = Scenario.EVENT_ID,
        recentlyUsedFlavors = Scenario.BURNED,
        knownAttendees = Scenario.ATTENDEES,
        organizerName = Scenario.ORGANIZER,
    )

    println("asking codex for a typed DeclineDeployment...")
    val out: DeclineDeployment = deploy.run(request)
    println("\n=== TYPED, FROM CODEX ===")
    println("flavor:  ${out.flavor}   (${out.flavor::class.simpleName}, not a String)")
    println("alibi:   ${out.fakeCalendarEventId ?: "none"}")
    println("message: ${out.messageToOrganizer.take(160)}")
    require(out.flavor !in Scenario.BURNED) { "picked a burned flavor" }
    println("\nburned-flavor check: passed")
    exitProcess(0)
}
