package jclaw

import jclaw.domain.DeclineCritique
import jclaw.domain.DeclineDeployment

/** Attempt count travels with this request, never in mutable strategy-wide state. */
data class ReviewAttempt(val plan: DeclineDeployment, val refinements: Int = 0)

enum class ReviewRoute { APPROVE, REFINE, BLOCK }

data class ReviewDecision(
    val attempt: ReviewAttempt,
    val route: ReviewRoute,
    val feedback: String,
)

fun reviewDecision(
    attempt: ReviewAttempt,
    critique: DeclineCritique?,
    maxRefinements: Int = 2,
): ReviewDecision = when {
    critique == null -> ReviewDecision(attempt, ReviewRoute.BLOCK, "Codex returned no valid verdict. Nothing can be sent.")
    critique.approved -> ReviewDecision(attempt, ReviewRoute.APPROVE, critique.feedback)
    attempt.refinements < maxRefinements -> ReviewDecision(attempt, ReviewRoute.REFINE, critique.feedback)
    else -> ReviewDecision(attempt, ReviewRoute.BLOCK, "Codex rejected the plan after $maxRefinements refinements: ${critique.feedback}")
}

enum class PipelineStageState { STARTED, COMPLETED, FAILED }
