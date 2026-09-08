package jclaw

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jclaw.domain.DeclineCritique
import jclaw.domain.DeclineDeployment
import jclaw.domain.ExcuseFlavor
import jclaw.domain.PlausibilityTier

class ReviewGateTest : StringSpec({
    val original = DeclineDeployment(
        flavor = ExcuseFlavor.DEADLINE,
        messageToOrganizer = "I have a delivery deadline that afternoon.",
        hallwayScript = "The delivery needs my attention.",
    )
    val revised = original.copy(
        flavor = ExcuseFlavor.ALREADY_PROFICIENT,
        messageToOrganizer = "I will be presenting a conference talk about building AI agents that afternoon.",
        hallwayScript = "I was presenting my AI agents talk.",
    )
    val rejection = DeclineCritique(
        tier = PlausibilityTier.THIN,
        approved = false,
        feedback = "The stated deadline is unsupported; use the actual conference commitment.",
    )
    val approval = DeclineCritique(
        tier = PlausibilityTier.AIRTIGHT,
        approved = true,
        feedback = "This gives Dana the relevant reason clearly.",
    )

    "rejection permits two refinements and then blocks the latest rejected plan" {
        val initial = reviewDecision(ReviewAttempt(original), rejection)
        initial.route shouldBe ReviewRoute.REFINE
        initial.feedback shouldBe rejection.feedback

        val firstRevision = ReviewAttempt(original.copy(messageToOrganizer = "First revision"), refinements = 1)
        val firstReview = reviewDecision(firstRevision, rejection)
        firstReview.route shouldBe ReviewRoute.REFINE

        val lastRevision = ReviewAttempt(original.copy(messageToOrganizer = "Second revision"), refinements = 2)
        val lastReview = reviewDecision(lastRevision, rejection)
        lastReview.route shouldBe ReviewRoute.BLOCK
        lastReview.attempt shouldBe lastRevision
        lastReview.feedback shouldContain rejection.feedback
    }

    "approval after refinement releases the revised plan including on the final review" {
        reviewDecision(ReviewAttempt(original), rejection).route shouldBe ReviewRoute.REFINE

        listOf(1, 2).forEach { refinements ->
            val attempt = ReviewAttempt(revised, refinements)
            val reviewed = reviewDecision(attempt, approval)
            reviewed.route shouldBe ReviewRoute.APPROVE
            reviewed.attempt shouldBe attempt
        }
    }

    "a missing verdict blocks immediately at every attempt" {
        listOf(0, 1, 2).forEach { refinements ->
            val attempt = ReviewAttempt(revised, refinements)
            val reviewed = reviewDecision(attempt, null)
            reviewed.route shouldBe ReviewRoute.BLOCK
            reviewed.attempt shouldBe attempt
        }
    }

    "one request exhausting its retries does not consume another request's allowance" {
        val firstRequest = ReviewAttempt(original, refinements = 2)
        reviewDecision(firstRequest, rejection).route shouldBe ReviewRoute.BLOCK

        val secondRequest = ReviewAttempt(revised)
        reviewDecision(secondRequest, rejection).route shouldBe ReviewRoute.REFINE
        reviewDecision(secondRequest.copy(refinements = 1), rejection).route shouldBe ReviewRoute.REFINE
        reviewDecision(firstRequest, rejection).route shouldBe ReviewRoute.BLOCK
        secondRequest.refinements shouldBe 0
    }

    "blocked results and chat replies never ask for confirmation or send" {
        val results = listOf(
            JclawResult.Blocked("No valid verdict"),
            JclawResult.Blocked("Codex rejected this plan", original),
            JclawResult.ChatReply("Hello, Baruch."),
        )
        results.forEach { result ->
            deliverApproved(
                result,
                confirm = { error("A blocked result or chat reply must not request confirmation") },
                send = { error("A blocked result or chat reply must never send") },
            ) shouldBe false
        }
    }

    "a human refusal holds even a critic-approved plan" {
        val confirmations = mutableListOf<DeclineDeployment>()
        deliverApproved(
            JclawResult.ReadyToSend(revised),
            confirm = { confirmations.add(it); false },
            send = { error("A human refusal must never send") },
        ) shouldBe false
        confirmations shouldBe listOf(revised)
    }

    "human confirmation sends exactly the latest approved plan once and in order" {
        val review = reviewDecision(ReviewAttempt(revised, refinements = 2), approval)
        review.route shouldBe ReviewRoute.APPROVE
        val actions = mutableListOf<Pair<String, DeclineDeployment>>()

        deliverApproved(
            JclawResult.ReadyToSend(review.attempt.plan),
            confirm = { actions.add("confirm" to it); true },
            send = { actions.add("send" to it) },
        ) shouldBe true

        actions shouldBe listOf("confirm" to revised, "send" to revised)
    }
})
