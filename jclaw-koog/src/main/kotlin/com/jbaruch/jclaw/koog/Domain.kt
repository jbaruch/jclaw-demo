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

@LLMDescription("A request to send an excuse for a social obligation on Baruch's behalf")
@Serializable
data class ExcuseRequest(
    @property:LLMDescription("Calendar event id Baruch is excusing himself from (from conference-mcp)")
    val eventId: String,

    @property:LLMDescription("Excuse flavors used at this venue or with these attendees in the last 90 days")
    val recentlyUsedFlavors: List<ExcuseFlavor>,

    @property:LLMDescription("Names of speakers attending — the excuse must not contradict any of them seeing Baruch")
    val knownAttendees: List<String>,

    @property:LLMDescription("Who organized the event — they will receive the excuse")
    val organizerName: String,
)

@LLMDescription("An excuse j-claw has drafted and is ready to send")
@Serializable
data class ExcuseDeployment(
    @property:LLMDescription("Excuse flavor selected")
    val flavor: ExcuseFlavor,

    @property:LLMDescription("The excuse message that will go to the organizer")
    val messageToOrganizer: String,

    @property:LLMDescription("Hallway script — what to say if any of the attendees asks the next day")
    val hallwayScript: String,
)

/** Intent classification from the routing subgraph at the head of the strategy graph. */
@Serializable
enum class IntentClassification { EXCUSE_REQUEST, CHAT }

/**
 * The classify subgraph's output — carries the routing decision PLUS the original
 * user message so downstream subgraphs (identifyExcuse / chatReply) can consume it.
 */
@LLMDescription("Routing decision for the next subgraph plus the original user message")
@Serializable
data class ClassifiedInput(
    @property:LLMDescription("Pick EXCUSE_REQUEST if the user is asking to decline / cancel / RSVP no to an event. Otherwise CHAT.")
    val intent: IntentClassification,
    @property:LLMDescription("The user's original message, verbatim, passed through unchanged")
    val userMessage: String,
)

/**
 * The strategy's terminal output — either a sent excuse or a free-form chat reply.
 * The routing subgraph picks the branch; only one of these is ever produced per run.
 */
@Serializable
sealed interface JclawResult {
    @Serializable
    data class ExcuseSent(val deployment: ExcuseDeployment) : JclawResult

    @Serializable
    data class ChatReply(val text: String) : JclawResult
}
