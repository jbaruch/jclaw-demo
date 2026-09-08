package jclaw

import jclaw.domain.DeclineCritique
import jclaw.domain.DeclineDeployment

/** The review transcript shown to the user, in the same order the workflow executes. */
sealed interface ReviewEvent {
    val attempt: ReviewAttempt

    data class Draft(override val attempt: ReviewAttempt) : ReviewEvent
    data class Verdict(override val attempt: ReviewAttempt, val critique: DeclineCritique) : ReviewEvent
}

internal fun ReviewEvent.chatText(): String = when (this) {
    is ReviewEvent.Draft -> {
        val label = if (attempt.refinements == 0) "Claude draft" else "Claude revised draft"
        "$label ${attempt.refinements + 1} (awaiting Codex review)\n" +
            "Flavor: ${attempt.plan.flavor}\n\n" +
            "Message:\n${attempt.plan.messageToOrganizer}\n\n" +
            "Hallway script:\n${attempt.plan.hallwayScript}"
    }
    is ReviewEvent.Verdict ->
        "Codex ${if (critique.approved) "approved" else "rejected"} draft ${attempt.refinements + 1}: ${critique.feedback}"
}

/** Announce the exact candidate before the judge is called, including every refinement. */
internal suspend fun ReviewAttempt.review(
    onEvent: suspend (ReviewEvent) -> Unit,
    judge: suspend (DeclineDeployment) -> DeclineCritique,
): ReviewDecision {
    onEvent(ReviewEvent.Draft(this))
    val critique = judge(plan)
    onEvent(ReviewEvent.Verdict(this, critique))
    return reviewDecision(this, critique)
}
