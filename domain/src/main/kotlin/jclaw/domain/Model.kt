package jclaw.domain

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * The excuse vocabulary. Flavors marked "burned" are already in the seeded
 * memory, so an agent worth its tokens must not reach for them a fourth time.
 */
@Serializable
public enum class ExcuseFlavor {
    @LLMDescription("A meeting that cannot move. BURNED - already used on this organizer.")
    CALENDAR_CONFLICT,

    @LLMDescription("Something at home. BURNED - already used on this organizer.")
    FAMILY_OBLIGATION,

    @LLMDescription("A customer emergency. BURNED - already used on this organizer.")
    CUSTOMER_ESCALATION,

    @LLMDescription("A hard delivery deadline the user genuinely has this week.")
    DEADLINE,

    @LLMDescription(
        "The user already has practical experience with the subject the session teaches."
    )
    ALREADY_PROFICIENT,

    @LLMDescription("The user openly says the session is pointless. Honest, and career-limiting.")
    EXISTENTIAL_CRISIS,
}

/** How well the excuse survives contact with People Ops. */
@Serializable
public enum class PlausibilityTier {
    @LLMDescription("Would hold up under close scrutiny and follow-up questions.")
    AIRTIGHT,

    @LLMDescription("Not checkable, but nobody would bother.")
    CREDIBLE,

    @LLMDescription("Would not survive one follow-up question.")
    THIN,

    @LLMDescription("Contradicts something the organizer can already see. Do not send.")
    HR_WILL_NOTICE,
}

@Serializable
@LLMDescription("A request to get the user out of a mandatory obligation")
public data class DeclineRequest(
    @property:LLMDescription("Calendar event the user is trying to get out of")
    val eventId: String,
    @property:LLMDescription("Excuse flavors already used with these people - never reuse one")
    val recentlyUsedFlavors: List<ExcuseFlavor>,
    @property:LLMDescription("People who would notice if the story does not hold up")
    val knownAttendees: List<String>,
    @property:LLMDescription("Who runs the session - they receive the decline")
    val organizerName: String,
)

@Serializable
@LLMDescription("A proposed decline awaiting review and human confirmation")
public data class DeclineDeployment(
    @property:LLMDescription("Excuse flavor selected")
    val flavor: ExcuseFlavor,
    @property:LLMDescription(
        "Id of a supporting event actually returned by a calendar tool, or null if " +
        "none exists. Drafting a plan does not create calendar events."
    )
    val fakeCalendarEventId: String? = null,
    @property:LLMDescription("The decline message that goes to the organizer")
    val messageToOrganizer: String,
    @property:LLMDescription("Hallway script - what the user says if asked about this tomorrow")
    val hallwayScript: String,
)

@Serializable
@LLMDescription("The critic's read on a staged decline")
public data class DeclineCritique(
    @property:LLMDescription("How well this survives contact with People Ops")
    val tier: PlausibilityTier,
    @property:LLMDescription("True only when the decline is safe to send")
    val approved: Boolean,
    @property:LLMDescription("What to fix, when not approved")
    val feedback: String,
)
