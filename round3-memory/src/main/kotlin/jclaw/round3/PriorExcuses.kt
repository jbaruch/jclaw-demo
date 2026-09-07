package jclaw.round3

import ai.koog.rag.base.TextDocument

/** One thing j-claw said on Baruch's behalf, and is now stuck with. */
data class Excuse(
    override val content: String,
    override val id: String? = null,
    override val metadata: Map<String, Any> = emptyMap(),
) : TextDocument

/** What j-claw already told Dana. Three stories, three burned flavors. */
object PriorExcuses {
    fun seed(): List<TextDocument> = listOf(
        Excuse(
            id = "compliance-refresher",
            content = "Declined the Annual Compliance Refresher run by Dana from People Ops. " +
                "Excuse flavor used: CALENDAR_CONFLICT. Told Dana it collided with a customer call that could not move.",
        ),
        Excuse(
            id = "ways-of-working",
            content = "Declined the Agile Ways of Working Alignment Workshop run by Dana from People Ops. " +
                "Excuse flavor used: FAMILY_OBLIGATION. Told Dana it was a family obligation and promised to catch the recording.",
        ),
        Excuse(
            id = "security-awareness-m4",
            content = "Declined Security Awareness Module 4: Phishing run by Dana from People Ops. " +
                "Excuse flavor used: CUSTOMER_ESCALATION. Told Dana a customer escalation had just landed on my desk.",
        ),
    )
}
