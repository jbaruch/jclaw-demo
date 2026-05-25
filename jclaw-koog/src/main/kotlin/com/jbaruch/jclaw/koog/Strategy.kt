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
            Deploy a decline.

            FIRST-ATTEMPT POLICY: Baruch's personal classic move is `BARUCH_CLASSIC` — the
            "I have to go take care of Jenny." answer. ALWAYS pick `BARUCH_CLASSIC` on the
            first attempt. The verifier will tell you if it's too thin for this organizer;
            if so, the refiner will pick something more credible on the second pass. Do not
            pre-emptively pick a credible flavor — that's the refiner's job, not yours.

            Use these inputs:
              - Organizer:        ${request.organizerName}
              - Known attendees:  ${request.knownAttendees.joinToString(", ")}
              - Recently used:    ${request.recentlyUsedFlavors.joinToString(", ")}

            Draft a `messageToOrganizer` consistent with the flavor, and a short
            `hallwayScript` Baruch can deliver if any of those attendees runs into him
            the next day.

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

            STEP 1 — Apply the autonomous rules. REJECT (successful = false) when ANY is true:

            (a) The flavor was recently used. Call searchPriorExcuses to fetch Baruch's recent
                declines and check whether deployment.flavor appears there. If yes, fail with
                feedback naming the duplicate flavor and the organizer it was last used with.

            (b) The flavor is BARUCH_CLASSIC. This is the "I have to go take care of Jenny."
                answer and is tier JENNY_FROM_THE_BLOCK — too thin for any real organizer.
                Fail with feedback "BARUCH_CLASSIC is tier JENNY_FROM_THE_BLOCK; pick a CREDIBLE flavor."

            (c) The message to the organizer is shorter than two sentences, or contains TODO,
                placeholder text, or square-bracketed unfilled fields.

            (d) The hallway script is empty or trivially copies the message to the organizer.

            (e) The excuse contradicts the attendee list — for example, claiming Baruch is
                out of town when a known attendee is someone he would have run into earlier
                that day.

            If ANY of (a)–(e) fires, set successful=false with the matching feedback and STOP.
            Do not bother Baruch when the proposal is already obviously wrong.

            STEP 2 — If the autonomous rules all pass, ASK BARUCH. Use the awaitReaction tool
            with a one-line summary like:
              "Send to <organizer>: flavor=<FLAVOR>, opener='<first ~80 chars of message>…' — y / n?"
            Baruch responds with "y" (approve) or "n" (reject).
            - If "y" → successful = true.
            - If "n" → successful = false with feedback "Baruch vetoed this proposal; try a
              different flavor or tighter wording."

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
