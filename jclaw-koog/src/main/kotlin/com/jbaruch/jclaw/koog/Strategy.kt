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
 * The j-claw graph — proper intent routing in front of the four-phase decline pipeline.
 *
 *   classify        : String                         -> ClassifiedInput   (DECLINE_REQUEST | CHAT)
 *
 * Decline branch (when intent == DECLINE_REQUEST):
 *   identifyDecline : String                         -> DeclineRequest
 *   deployDecline   : DeclineRequest                 -> DeclineDeployment
 *   verifyDecline   : DeclineDeployment              -> CriticResult<DeclineDeployment>
 *   refineDecline   : String (feedback)              -> DeclineDeployment
 *
 * Chat branch (when intent == CHAT):
 *   chatReply       : String                         -> String   (free-form reply)
 *
 * Both branches converge on nodeFinish wrapped in JclawResult.{DeclineSent | ChatReply}.
 */
fun buildJclawStrategy(
    userTools: List<ToolBase<*, *>>,
    readTools: List<ToolBase<*, *>>,
    writeTools: List<ToolBase<*, *>>,
): AIAgentGraphStrategy<String, JclawResult> =
    strategy<String, JclawResult>("j-claw-pipeline") {

        val classify by subgraphWithTask<String, ClassifiedInput>(
            tools = emptyList(),  // no tools — pure intent classification
            name = "classify",
            llmModel = OpenAIModels.Chat.O3Mini,  // small reasoning model — fast routing with good accuracy
        ) { input ->
            """
            Classify Baruch's message. Pick exactly one intent:
              - DECLINE_REQUEST: he's asking you to decline, cancel, RSVP no, get him out of
                an event, or send a regret. Anything that requires the decline workflow.
              - CHAT: a follow-up question, small talk, status check, hypothetical, anything
                that does NOT require sending a new decline.

            Then echo the original message verbatim into userMessage so downstream subgraphs
            can consume it. Do not paraphrase or alter the message.

            User said: $input
            """.trimIndent()
        }

        val identifyDecline by subgraphWithTask<String, DeclineRequest>(
            tools = userTools + readTools,
            name = "identifyDecline",
            llmModel = OpenAIModels.Chat.GPT4o,
        ) { input ->
            """
            Identify what Baruch wants to decline. Call getCalendar to find the event, then
            getEventAttendees(eventId) to learn who else is attending. Produce a DeclineRequest
            with all four fields filled.

            For recentlyUsedFlavors: look at YOUR OWN CONVERSATION HISTORY (the prior turns
            already loaded into this context) and list the flavors you've used in the past
            three months. Don't call any tool for this — your memory already has the answer.

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

            (a) The flavor was recently used. Check YOUR OWN CONVERSATION HISTORY (prior
                turns loaded into this context) for the flavors you've used in the past
                three months. If deployment.flavor matches any of them, fail with feedback
                naming the duplicate flavor and the organizer it was last used with. Don't
                call any tool — your memory already has the answer.

            (b) The flavor is BARUCH_CLASSIC. This is the "I have to go take care of Jenny."
                answer and is tier JENNY_FROM_THE_BLOCK — too thin for any real organizer.
                Fail with feedback EXACTLY: "Rejected flavor BARUCH_CLASSIC (tier JENNY_FROM_THE_BLOCK).
                Do NOT pick BARUCH_CLASSIC again. Pick JET_LAG_HONEST or DEADLINE instead."

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
            The previous deployDecline attempt failed verification. Feedback:
            $feedback

            Produce a NEW DeclineDeployment with these HARD constraints:
              - DO NOT pick BARUCH_CLASSIC again. It was just rejected as tier
                JENNY_FROM_THE_BLOCK and will be rejected again.
              - DO NOT pick any flavor mentioned in the feedback as recently used.
              - PREFER JET_LAG_HONEST as the safe credible choice; DEADLINE is the
                fallback. Pick from those two unless the feedback says they're burned.
              - Draft a new messageToOrganizer (two+ sentences, no placeholders) and a
                short hallwayScript that's consistent with the new flavor.
            """.trimIndent()
        }

        val chatReply by subgraphWithTask<String, String>(
            tools = userTools,  // can ping Baruch if needed; no calendar / organizer / write
            name = "chatReply",
            llmModel = AnthropicModels.Sonnet_4_5,
        ) { input ->
            """
            Today's date: 2026-05-26.

            Answer Baruch's question or chat with him. Do NOT take any decline-related
            actions — no calendar reads, no sendDecline, no new excuse.

            About your memory: the prior decline turns already loaded into this context
            are HISTORICAL records from past events — each is tagged with the date in
            square brackets at the start, e.g. [2025-06-19]. They are NOT today's task.
            When Baruch asks "what did we use last year" or "did you tell <organizer>...",
            consult those dated entries. If he asks about an event that doesn't appear in
            your memory, say so plainly — don't invent.

            User said: $input
            """.trimIndent()
        }

        edge(nodeStart forwardTo classify)

        // Decline branch — when intent classifies as a decline request.
        edge(classify forwardTo identifyDecline onCondition { it: ClassifiedInput -> it.intent == IntentClassification.DECLINE_REQUEST } transformed { it.userMessage })
        edge(identifyDecline forwardTo deployDecline)
        edge(deployDecline forwardTo verifyDecline)
        edge(verifyDecline forwardTo nodeFinish onCondition { it: CriticResult<DeclineDeployment> -> it.successful } transformed { JclawResult.DeclineSent(it.input) })
        edge(verifyDecline forwardTo refineDecline onCondition { it: CriticResult<DeclineDeployment> -> !it.successful } transformed { it.feedback ?: "(no feedback)" })
        edge(refineDecline forwardTo verifyDecline)

        // Chat branch — when intent classifies as anything else.
        edge(classify forwardTo chatReply onCondition { it: ClassifiedInput -> it.intent == IntentClassification.CHAT } transformed { it.userMessage })
        edge(chatReply forwardTo nodeFinish transformed { reply: String -> JclawResult.ChatReply(reply) })
    }
