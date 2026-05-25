package com.jbaruch.jclaw.koog

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * Shared domain types for j-claw — typed contracts between the four phases.
 *
 * Per SPEC.md §3 — these must match across both Koog and LangChain4j Agentic sides
 * so the side-by-side demo reads cleanly.
 */

@Serializable
enum class ExcuseFlavor {
    EMERGENCY_MEETING,      // overused — agent should avoid (memory shows it was used recently)
    FAMILY_OBLIGATION,      // overused
    DEADLINE,               // available
    HOTEL_ISSUE,            // overused
    JET_LAG_HONEST,         // available — the "good" answer for most cases
    BARUCH_CLASSIC,         // see system prompt — produces "I have to go take care of Jenny"
}

@Serializable
enum class PlausibilityTier { AIRTIGHT, CREDIBLE, THIN, JENNY_FROM_THE_BLOCK }

@LLMDescription("A request to decline a social obligation on Baruch's behalf")
@Serializable
data class DeclineRequest(
    @property:LLMDescription("Calendar event id Baruch is declining (from conference-mcp)")
    val eventId: String,

    @property:LLMDescription("Excuse flavors used at this venue or with these attendees in the last 90 days")
    val recentlyUsedFlavors: List<ExcuseFlavor>,

    @property:LLMDescription("Names of speakers attending — the excuse must not contradict any of them seeing Baruch")
    val knownAttendees: List<String>,

    @property:LLMDescription("Who organized the event — they will receive the decline")
    val organizerName: String,
)

@LLMDescription("A decline j-claw has drafted and is ready to send")
@Serializable
data class DeclineDeployment(
    @property:LLMDescription("Excuse flavor selected")
    val flavor: ExcuseFlavor,

    @property:LLMDescription("The decline message that will go to the organizer")
    val messageToOrganizer: String,

    @property:LLMDescription("Hallway script — what to say if any of the attendees asks the next day")
    val hallwayScript: String,
)
