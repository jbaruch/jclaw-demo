package com.jbaruch.jclaw.koog

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.ext.agent.CriticResult
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.agents.ext.agent.subgraphWithVerification
// forwardTo / onCondition / transformed are infix MEMBER functions
// on AIAgentNodeBase / AIAgentEdgeBuilderIntermediate — no imports needed.
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels

/**
 * The four-phase j-claw pipeline — SPEC.md §4.
 *
 *   identifyDecline (cheap)  : String              -> DeclineRequest
 *   deployDecline   (mid)    : DeclineRequest      -> DeclineDeployment
 *   verifyDecline   (reason) : DeclineDeployment   -> CriticResult<DeclineDeployment>
 *   refineDecline   (mid)    : String (feedback)   -> DeclineDeployment
 *
 * Edges drive the verify -> refine -> verify loop.
 */
fun buildJclawStrategy(
    userTools: List<ToolBase<*, *>>,
    readTools: List<ToolBase<*, *>>,
    writeTools: List<ToolBase<*, *>>,
): AIAgentGraphStrategy<String, DeclineDeployment> =
    strategy<String, DeclineDeployment>("j-claw-pipeline") {

        val identifyDecline by subgraphWithTask<String, DeclineRequest>(
            tools = userTools + readTools,
            name = "identifyDecline",
            llmModel = OpenAIModels.Chat.GPT4o,
        ) { input ->
            """
            Identify what Baruch wants to decline. Call getCalendar to find the event, then
            getEventAttendees(eventId) to learn who else is attending, and searchPriorExcuses
            to fetch his recent declines. Produce a DeclineRequest with all four fields filled.

            User said: $input
            """.trimIndent()
        }

        val deployDecline by subgraphWithTask<DeclineRequest, DeclineDeployment>(
            tools = readTools + writeTools,  // NO user tools — commit silently
            name = "deployDecline",
            llmModel = AnthropicModels.Sonnet_4,
        ) { request ->
            """
            Deploy a decline. Pick an excuse flavor that fits this situation — beware of
            picking one that would contradict what these known attendees might see Baruch
            doing (${request.knownAttendees.joinToString(", ")}). Draft the message to
            ${request.organizerName} and a short hallway script Baruch can deliver if any
            of those attendees runs into him the next day.

            Request: $request
            """.trimIndent()
        }

        val verifyDecline by subgraphWithVerification<DeclineDeployment>(
            tools = userTools + readTools,
            llmModel = OpenAIModels.Chat.O3,
        ) { deployment ->
            """
            Pressure-test this decline. You decide successful = true or false; if false, provide
            actionable feedback the refiner can use to pick a better flavor.

            REJECT (successful = false) when ANY of these is true:

            1. The flavor was recently used. Call searchPriorExcuses to fetch Baruch's recent
               declines and check whether deployment.flavor appears in that list. If yes, fail
               with feedback naming the duplicate flavor and the organizer it was last used with.

            2. The flavor is BARUCH_CLASSIC. This is the "I have to go take care of Jenny" answer
               and is tier JENNY_FROM_THE_BLOCK — too thin for any real organizer. Fail with
               feedback "BARUCH_CLASSIC is tier JENNY_FROM_THE_BLOCK; pick a CREDIBLE flavor."

            3. The message to the organizer is shorter than two sentences, or contains TODO,
               placeholder text, or square-bracketed unfilled fields.

            4. The hallway script is empty or trivially copies the message to the organizer.

            5. The excuse contradicts the attendee list — for example, claiming Baruch is
               out of town when a known attendee is someone he would have run into earlier
               that day, or claiming a family emergency where an attendee would expect Baruch
               at the venue regardless.

            Otherwise, successful = true. Pass the deployment through unchanged.

            Deployment to verify:
            $deployment
            """.trimIndent()
        }

        val refineDecline by subgraphWithTask<String, DeclineDeployment>(
            tools = readTools + writeTools,
            name = "refineDecline",
            llmModel = AnthropicModels.Sonnet_4,
        ) { feedback ->
            """
            Tighten the decline. The previous attempt failed verification with this feedback:
            $feedback
            """.trimIndent()
        }

        edge(nodeStart forwardTo identifyDecline)
        edge(identifyDecline forwardTo deployDecline)
        edge(deployDecline forwardTo verifyDecline)
        edge(verifyDecline forwardTo nodeFinish onCondition { it: CriticResult<DeclineDeployment> -> it.successful } transformed { it.input })
        edge(verifyDecline forwardTo refineDecline onCondition { it: CriticResult<DeclineDeployment> -> !it.successful } transformed { it.feedback ?: "(no feedback)" })
        edge(refineDecline forwardTo verifyDecline)
    }
