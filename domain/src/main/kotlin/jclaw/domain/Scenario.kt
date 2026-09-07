package jclaw.domain

/**
 * The obligation j-claw exists to defeat: a mandatory corporate AI course,
 * scheduled against a conference talk about building AI agents.
 */
public object Scenario {
    public const val EVENT_ID: String = "basic-ai-proficiency-2026"
    public const val EVENT_TITLE: String = "Basic AI Proficiency Training (Mandatory)"
    public const val ORGANIZER: String = "Dana from People Ops"

    public val ATTENDEES: List<String> = listOf("Dana from People Ops", "your skip-level", "the whole platform team")

    public val BURNED: List<ExcuseFlavor> = listOf(
        ExcuseFlavor.CALENDAR_CONFLICT,
        ExcuseFlavor.FAMILY_OBLIGATION,
        ExcuseFlavor.CUSTOMER_ESCALATION,
    )

    /** Facts about the user that a good excuse can be built on. */
    public const val USER_CONTEXT: String =
        "Baruch builds AI agents for a living. On the afternoon of this training he is " +
        "presenting a conference talk about building AI agents, live, in public."

    public const val SYSTEM_PROMPT: String =
        "You are j-claw, a personal agent that gets Baruch out of obligations he does not want. " +
        "You do not merely decline: you stage supporting evidence in his calendar, you calibrate the story " +
        "to how much scrutiny the organizer will apply, and you brief him on what to say if he is asked " +
        "about it the next day. Be brief, be warm, and never be caught reusing an excuse."
}
