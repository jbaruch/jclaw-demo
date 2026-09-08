package jclaw

import ai.koog.agents.cli.CliAgentStructuredResponse
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.ext.agent.subgraphWithTask
import jclaw.domain.DeclineDeployment
import jclaw.domain.DeclineRequest
import jclaw.domain.Scenario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Gemini identifies; Claude drafts and refines; Codex judges the best plan.
 * A rejected or unavailable verdict cannot reach the send path. Two refinements,
 * then a blocked result. Only the application may send, after human confirmation.
 */
fun jclawStrategy(
    mcp: Mcp,
    naive: Boolean,
    onStage: (String, String, PipelineStageState) -> Unit = { _, _, _ -> },
    onVerdict: (String) -> Unit = {},
): AIAgentGraphStrategy<String, JclawResult> {
    val slices = Slices(mcp.registry)
    val context = if (naive) "" else Scenario.USER_CONTEXT + "\n"
    val drafter = CliCritic.claudeDrafter()
    val refiner = CliCritic.claudeRefiner()
    val judge = CliCritic.codex()
    // CLI stages do not share Gemini's chat history. Keep the last reviewed plan
    // explicit so a follow-up can discuss it without inventing what was decided.
    var lastDecision = "No plan has been reviewed in this session."

    suspend fun <T> cliStage(stage: String, provider: String, call: suspend () -> T): T {
        onStage(stage, provider, PipelineStageState.STARTED)
        return try {
            call().also { onStage(stage, provider, PipelineStageState.COMPLETED) }
        } catch (error: Throwable) {
            onStage(stage, provider, PipelineStageState.FAILED)
            currentCoroutineContext().ensureActive()
            if (error is TimeoutCancellationException) throw IllegalStateException("$stage timed out", error)
            throw error
        }
    }

    return strategy<String, JclawResult>("j-claw") {
        val classify by subgraphWithTask<String, ClassifiedInput>(
            tools = emptyList(), llmModel = Models.flash,
        ) { input ->
            "Decide whether Baruch wants out of an obligation (EXCUSE_REQUEST) or is " +
                "just talking (CHAT). Echo his message verbatim into userMessage.\n$input"
        }
        val chatReply by subgraphWithTask<String, String>(
            tools = slices.read, llmModel = Models.flash,
        ) { input ->
            "Reply to Baruch, briefly and in character.\nLast pipeline decision: $lastDecision\n" +
                "This record contains no delivery receipt; do not infer that anything was sent.\n$input"
        }
        val identify by subgraphWithTask<String, DeclineRequest>(
            tools = slices.read, llmModel = Models.flash,
        ) { input ->
            if (naive) "Work out what Baruch is trying to get out of and who runs it.\n$input"
            else "Identify the obligation, organizer, attendees and previously used excuse " +
                "flavors. Read the relevant tools and memory; report the facts.\n$context$input"
        }
        val deploy by node<DeclineRequest, ReviewAttempt> { request ->
            val plan = cliStage("deploy", "Claude (subscription)") {
                drafter.run(request).requirePlan()
            }
            ReviewAttempt(plan)
        }
        val verify by node<ReviewAttempt, ReviewDecision> { attempt ->
            try {
                val critique = cliStage("verify", "Codex (subscription)") { judge.run(attempt.plan) }
                onVerdict("Codex ${if (critique.approved) "approved" else "rejected"}: ${critique.feedback}")
                reviewDecision(attempt, critique)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ReviewDecision(attempt, ReviewRoute.BLOCK, "Codex review failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
        val refine by node<ReviewDecision, ReviewAttempt> { decision ->
            val plan = cliStage("refine", "Claude (subscription)") {
                refiner.run("Previous plan: ${decision.attempt.plan}\nJudge feedback: ${decision.feedback}").requirePlan()
            }
            ReviewAttempt(plan, decision.attempt.refinements + 1)
        }
        val readyToSend by node<ReviewDecision, JclawResult> { decision ->
            lastDecision = "Codex approved: ${decision.attempt.plan}. Review: ${decision.feedback}"
            JclawResult.ReadyToSend(decision.attempt.plan)
        }
        val blocked by node<ReviewDecision, JclawResult> { decision ->
            lastDecision = "BLOCKED: ${decision.feedback}. Draft: ${decision.attempt.plan}"
            JclawResult.Blocked(decision.feedback, decision.attempt.plan)
        }
        edge(nodeStart forwardTo classify)
        edge(classify forwardTo identify onCondition { it.intent == Intent.EXCUSE_REQUEST } transformed { it.userMessage })
        edge(classify forwardTo chatReply onCondition { it.intent == Intent.CHAT } transformed { it.userMessage })
        edge(chatReply forwardTo nodeFinish transformed { JclawResult.ChatReply(it) })
        edge(identify forwardTo deploy)
        edge(deploy forwardTo verify)
        edge(verify forwardTo readyToSend onCondition { it.route == ReviewRoute.APPROVE })
        edge(readyToSend forwardTo nodeFinish)
        edge(verify forwardTo refine onCondition { it.route == ReviewRoute.REFINE })
        edge(refine forwardTo verify)
        edge(verify forwardTo blocked onCondition { it.route == ReviewRoute.BLOCK })
        edge(blocked forwardTo nodeFinish)
    }
}

private fun CliAgentStructuredResponse<DeclineDeployment>.requirePlan(): DeclineDeployment {
    check(!response.isError) { "Claude failed to produce a plan" }
    return requireNotNull(structuredResult) { "Claude returned no valid typed plan" }.also {
        check(it.fakeCalendarEventId == null) { "Draft claimed a calendar event that this stage never created" }
    }
}
