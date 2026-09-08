package jclaw

import ai.koog.agents.cli.CliAgentStructuredResponse
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.tools.ToolBase
import ai.koog.prompt.message.Message
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.ext.agent.subgraphWithTask
import jclaw.domain.DeclineCritique
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
    skills: AgentSkills = AgentSkills.EMPTY,
    onStage: (String, String, PipelineStageState) -> Unit = { _, _, _ -> },
    onReview: (ReviewEvent) -> Unit = {},
): AIAgentGraphStrategy<String, JclawResult> {
    val drafter = CliCritic.claudeDrafter()
    val refiner = CliCritic.claudeRefiner()
    val judge = CliCritic.codex()
    return jclawStrategy(
        readTools = Slices(mcp.registry).read, naive = naive, skills = skills,
        draft = { drafter.run(it).requirePlan() },
        refinePlan = { refiner.run(it).requirePlan() },
        judgePlan = { judge.run(it) }, onStage = onStage, onReview = onReview,
    )
}

/** The same native graph with model boundaries supplied independently of their transports. */
internal fun jclawStrategy(
    readTools: List<ToolBase<*, *>>,
    naive: Boolean,
    skills: AgentSkills = AgentSkills.EMPTY,
    draft: suspend (DeclineRequest) -> DeclineDeployment,
    refinePlan: suspend (String) -> DeclineDeployment,
    judgePlan: suspend (DeclineDeployment) -> DeclineCritique,
    onStage: (String, String, PipelineStageState) -> Unit = { _, _, _ -> },
    onReview: (ReviewEvent) -> Unit = {},
): AIAgentGraphStrategy<String, JclawResult> {
    val context = if (naive) "" else Scenario.USER_CONTEXT + "\n"
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
        val turnInput = createStorageKey<String>("conversation-input")
        val draftRequest = createStorageKey<DeclineRequest>("draft-request")
        val turnMessages = createStorageKey<List<Message>>("conversation-turn")
        val turnReviewMessages = createStorageKey<List<String>>("conversation-review-messages")
        val beginTurn by node<String, String> { input ->
            storage.set(turnInput, input)
            storage.set(turnReviewMessages, emptyList())
            llm.writeSession {
                appendPrompt { user(input) }
                storage.set(turnMessages, prompt.messages)
            }
            input
        }
        val classify by subgraphWithTask<String, ClassifiedInput>(
            tools = emptyList(), llmModel = Models.flash,
        ) { input ->
            classifyTask(input)
        }
        val chatReply by subgraphWithTask<String, String>(
            tools = readTools + skills.registry.tools, llmModel = Models.flash,
        ) { input ->
            "Reply to the user's current request using the conversation. " +
                "Apply a matching runtime skill when useful.\n$input"
        }
        val identify by subgraphWithTask<String, DeclineRequest>(
            tools = readTools, llmModel = Models.flash,
        ) { input ->
            identifyTask(storage.getValue(turnInput), context, naive)
        }
        val deploy by node<DeclineRequest, ReviewAttempt> { identified ->
            // The exact request must survive both model handoffs, even if an echo is incomplete.
            val request = identified.copy(userInstruction = storage.getValue(turnInput))
            storage.set(draftRequest, request)
            val plan = cliStage("deploy", "Claude (subscription)") { draft(request) }
            ReviewAttempt(plan)
        }
        val verify by node<ReviewAttempt, ReviewDecision> { attempt ->
            try {
                attempt.review(
                    onEvent = { event ->
                        onReview(event)
                        storage.set(turnReviewMessages, storage.get(turnReviewMessages).orEmpty() + event.chatText())
                    },
                    judge = { plan -> cliStage("verify", "Codex (subscription)") { judgePlan(plan) } },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ReviewDecision(attempt, ReviewRoute.BLOCK, "Codex review failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
        val refine by node<ReviewDecision, ReviewAttempt> { decision ->
            val plan = cliStage("refine", "Claude (subscription)") {
                refinePlan("REQUEST: ${storage.getValue(draftRequest)}\n" +
                    "Previous plan: ${decision.attempt.plan}\nJudge feedback: ${decision.feedback}")
            }
            ReviewAttempt(plan, decision.attempt.refinements + 1)
        }
        val readyToSend by node<ReviewDecision, JclawResult> { decision ->
            JclawResult.ReadyToSend(decision.attempt.plan)
        }
        val blocked by node<ReviewDecision, JclawResult> { decision ->
            JclawResult.Blocked(decision.feedback, decision.attempt.plan)
        }
        val rememberReply by node<JclawResult, JclawResult> { result ->
            // Persist the actual exchange, including CLI-produced drafts, through ChatMemory.
            // Classifier/worker instructions and finalize-task JSON are internal to this run.
            val exchange = storage.getValue(turnMessages)
            val reviewMessages = storage.get(turnReviewMessages).orEmpty()
            llm.writeSession {
                prompt = prompt.withMessages { exchange }
                appendPrompt {
                    reviewMessages.forEach { assistant(it) }
                    assistant(result.conversationText())
                }
            }
            result
        }
        edge(nodeStart forwardTo beginTurn)
        edge(beginTurn forwardTo classify)
        edge(classify forwardTo identify onCondition { it.intent == Intent.EXCUSE_REQUEST } transformed { it.userMessage })
        edge(classify forwardTo chatReply onCondition { it.intent == Intent.CHAT } transformed { it.userMessage })
        edge(chatReply forwardTo rememberReply transformed { JclawResult.ChatReply(it) })
        edge(identify forwardTo deploy)
        edge(deploy forwardTo verify)
        edge(verify forwardTo readyToSend onCondition { it.route == ReviewRoute.APPROVE })
        edge(readyToSend forwardTo rememberReply)
        edge(verify forwardTo refine onCondition { it.route == ReviewRoute.REFINE })
        edge(refine forwardTo verify)
        edge(verify forwardTo blocked onCondition { it.route == ReviewRoute.BLOCK })
        edge(blocked forwardTo rememberReply)
        edge(rememberReply forwardTo nodeFinish)
    }
}

private fun CliAgentStructuredResponse<DeclineDeployment>.requirePlan(): DeclineDeployment {
    check(!response.isError) { "Claude failed to produce a plan" }
    return requireNotNull(structuredResult) { "Claude returned no valid typed plan" }.also {
        check(it.fakeCalendarEventId == null) { "Draft claimed a calendar event that this stage never created" }
    }
}

/** Editing supplied text is ordinary chat, even when that text mentions an obligation. */
internal fun classifyTask(input: String): String = """
    Route the user's current request by what they want you to do, using the preceding
    conversation to resolve references to an earlier plan.
    EXCUSE_REQUEST: they ask you to develop a plan to get them out of an obligation,
    including asking for another excuse or a different approach to a previous plan.
    Declining to send a proposed plan while requesting a replacement starts a new workflow.
    CHAT: writing, rewriting, editing, translating, using a skill, answering questions,
    ordinary conversation, or discussing a previous plan. A quoted message about an
    obligation is source text to edit, not a request to launch the decline workflow.
    Requests to change style or intensity are CHAT. When uncertain, choose CHAT and
    clarify what the user needs. Echo the user's message verbatim into userMessage.
    User message:
    $input
""".trimIndent()


internal fun identifyTask(input: String, context: String, naive: Boolean): String = """
    Identify the obligation, organizer and attendees using the conversation.
    ${if (naive) "" else "Read the relevant tools and memory; report the facts and recently used flavors."}
    Preserve the user's current instruction. When they request another approach to an
    earlier plan, resolve the obligation from the conversation and list its previously
    proposed flavors separately. The new plan must avoid those suggestions, including
    a held draft. A suggestion is not a successful send and does not burn a memory flavor.
    Do not treat an unrelated earlier obligation as the current one.
    CONTEXT: $context
    USER INSTRUCTION:
    $input
""".trimIndent()
