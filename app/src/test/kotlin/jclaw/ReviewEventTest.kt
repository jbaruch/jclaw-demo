package jclaw

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jclaw.domain.DeclineCritique
import jclaw.domain.DeclineDeployment
import jclaw.domain.ExcuseFlavor
import jclaw.domain.PlausibilityTier

class ReviewEventTest : StringSpec({
    val draft = DeclineDeployment(
        flavor = ExcuseFlavor.DEADLINE,
        messageToOrganizer = "First message, exactly as drafted.\nIncluding its second line.",
        hallwayScript = "The original hallway answer.",
    )
    val revised = draft.copy(messageToOrganizer = "The revised message.", hallwayScript = "The revised hallway answer.")
    val rejection = DeclineCritique(PlausibilityTier.THIN, false, "This proposed plan needs another pass.")
    val approval = DeclineCritique(PlausibilityTier.AIRTIGHT, true, "This version is ready to consider.")

    "each exact draft is visible before its judge call and corresponding verdict" {
        val events = mutableListOf<ReviewEvent>()
        val order = mutableListOf<String>()
        val decisions = listOf(ReviewAttempt(draft), ReviewAttempt(revised, refinements = 1)).mapIndexed { index, attempt ->
            attempt.review(
                onEvent = {
                    events += it
                    order += if (it is ReviewEvent.Draft) "draft ${index + 1}" else "verdict ${index + 1}"
                },
                judge = { plan ->
                    events.last() shouldBe ReviewEvent.Draft(attempt)
                    plan shouldBe attempt.plan
                    order += "judge ${index + 1}"
                    if (index == 0) rejection else approval
                },
            )
        }
        order shouldBe listOf("draft 1", "judge 1", "verdict 1", "draft 2", "judge 2", "verdict 2")
        events shouldBe listOf(
            ReviewEvent.Draft(ReviewAttempt(draft)), ReviewEvent.Verdict(ReviewAttempt(draft), rejection),
            ReviewEvent.Draft(ReviewAttempt(revised, 1)), ReviewEvent.Verdict(ReviewAttempt(revised, 1), approval),
        )
        decisions.map { it.route } shouldBe listOf(ReviewRoute.REFINE, ReviewRoute.APPROVE)
        decisions.last().attempt.plan shouldBe revised
    }

    "presentation includes the complete message and hallway script with a linked attempt number" {
        val first = ReviewEvent.Draft(ReviewAttempt(draft)).chatText()
        first shouldContain "Claude draft 1 (awaiting Codex review)"
        first shouldContain "Flavor: DEADLINE"
        first shouldContain draft.messageToOrganizer
        first shouldContain draft.hallwayScript
        ReviewEvent.Draft(ReviewAttempt(revised, 1)).chatText() shouldContain "Claude revised draft 2"
        ReviewEvent.Verdict(ReviewAttempt(draft), rejection).chatText() shouldBe
            "Codex rejected draft 1: ${rejection.feedback}"
        ReviewEvent.Verdict(ReviewAttempt(revised, 1), approval).chatText() shouldBe
            "Codex approved draft 2: ${approval.feedback}"
    }

    "a failed judge still leaves its draft visible and never invents a verdict" {
        val events = mutableListOf<ReviewEvent>()
        val attempt = ReviewAttempt(revised, refinements = 2)
        shouldThrow<IllegalStateException> {
            attempt.review(onEvent = { events += it }, judge = { error("Judge unavailable") })
        }
        events shouldBe listOf(ReviewEvent.Draft(attempt))
    }
    "a new request starts at draft one after the previous request exhausts refinements" {
        val firstRequest = mutableListOf<ReviewEvent>()
        ReviewAttempt(draft, refinements = 2).review(
            onEvent = { firstRequest += it }, judge = { rejection },
        ).route shouldBe ReviewRoute.BLOCK
        val nextRequest = mutableListOf<ReviewEvent>()
        ReviewAttempt(revised).review(
            onEvent = { nextRequest += it }, judge = { approval },
        ).route shouldBe ReviewRoute.APPROVE
        nextRequest shouldBe listOf(
            ReviewEvent.Draft(ReviewAttempt(revised)),
            ReviewEvent.Verdict(ReviewAttempt(revised), approval),
        )
        nextRequest.first().chatText() shouldContain "Claude draft 1"
    }

})
