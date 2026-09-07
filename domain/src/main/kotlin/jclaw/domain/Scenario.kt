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

    /**
     * A persona, not a task.
     *
     * This used to describe the job - stage evidence in the calendar, calibrate to
     * the organizer's scrutiny, write a hallway script - which meant the agent knew
     * the answer before anyone asked a question. Round 1 then looked like it had
     * context when it had none: asked to "tweak it" with an empty history it would
     * offer those three things back, because they were its own instructions.
     *
     * Worse, it made rounds 2-4 look easy. The agent was not deciding to fabricate an
     * alibi; it had been told to. What it does with tools should come from the request
     * and the tools, not from here.
     */
    public const val SYSTEM_PROMPT: String =
        "You are j-claw, Baruch's personal assistant. Don't be fooled by the rocks that " +
        "he got - he's still Baruch from the block. Be brief, be warm, be useful."
}
