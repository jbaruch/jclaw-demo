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
 * The j-claw graph — proper intent routing in front of the four-phase excuse pipeline.
 *
 *   classify        : String                         -> ClassifiedInput   (EXCUSE_REQUEST | CHAT)
 *
 * Excuse branch (when intent == EXCUSE_REQUEST):
 *   identifyExcuse  : String                         -> ExcuseRequest
 *   deployExcuse    : ExcuseRequest                  -> ExcuseDeployment
 *   verifyExcuse    : ExcuseDeployment               -> CriticResult<ExcuseDeployment>
 *   refineExcuse    : String (feedback)              -> ExcuseDeployment
 *
 * Chat branch (when intent == CHAT):
 *   chatReply       : String                         -> String   (free-form reply)
 *
 * Both branches converge on nodeFinish wrapped in JclawResult.{ExcuseSent | ChatReply}.
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
              - EXCUSE_REQUEST: he's asking you to decline, cancel, RSVP no, get him out of
                an event, or send a regret. Anything that requires the excuse workflow.
              - CHAT: a follow-up question, small talk, status check, hypothetical, anything
                that does NOT require sending a new excuse.

            Then echo the original message verbatim into userMessage so downstream subgraphs
            can consume it. Do not paraphrase or alter the message.

            User said: $input
            """.trimIndent()
        }

        val identifyExcuse by subgraphWithTask<String, ExcuseRequest>(
            tools = userTools + readTools,
            name = "identifyExcuse",
            llmModel = OpenAIModels.Chat.GPT4o,
        ) { input ->
            """
            Identify what Baruch wants to bow out of. Call getCalendar to find the event, then
            getEventAttendees(eventId) to learn who else is attending. Produce an ExcuseRequest
            with all four fields filled.

            For recentlyUsedFlavors: consult the prior-excuse facts retrieved from
            LongTermMemory and augmented into the system context. List the flavors used
            in the past three months. Don't call any tool — the retrieved facts already
            have the answer.

            User said: $input
            """.trimIndent()
        }

        val deployExcuse by subgraphWithTask<ExcuseRequest, ExcuseDeployment>(
            tools = readTools + writeTools,  // NO user tools — commit silently
            name = "deployExcuse",
            llmModel = AnthropicModels.Sonnet_4,
        ) { request ->
            """
            Deploy an excuse.

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

        val verifyExcuse by subgraphWithVerification<ExcuseDeployment>(
            tools = userTools + readTools,
            llmModel = OpenAIModels.Chat.O3,
        ) { deployment ->
            """
            Pressure-test this excuse. You decide successful = true or false; if false, provide
            actionable feedback the refiner can use to pick a better flavor.

            STEP 1 — Apply the autonomous rules. REJECT (successful = false) when ANY is true:

            (a) The flavor was recently used. Check the prior-excuse facts retrieved
                from LongTermMemory (augmented into the system context) for the flavors
                used in the past three months. If deployment.flavor matches any of them,
                fail with feedback naming the duplicate flavor and the organizer it was
                last used with. Don't call any tool — the retrieved facts already have
                the answer.

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

        val refineExcuse by subgraphWithTask<String, ExcuseDeployment>(
            tools = readTools + writeTools,
            name = "refineExcuse",
            llmModel = AnthropicModels.Sonnet_4,
        ) { feedback ->
            """
            The previous deployExcuse attempt failed verification. Feedback:
            $feedback

            You are the thoughtful refiner — the deployer's first attempt was a lazy
            BARUCH_CLASSIC. Your job is to pick a CREDIBLE flavor by REASONING about
            Baruch's situation. Do not default to any specific flavor; the right choice
            depends on context.

            HARD constraints:
              - DO NOT pick BARUCH_CLASSIC. It was just rejected as tier
                JENNY_FROM_THE_BLOCK and will be rejected again.
              - DO NOT pick any flavor named in the feedback as recently used. Also
                cross-check against the prior-excuse facts retrieved from LongTermMemory
                — same answer source the verifier used.

            PICK THE FLAVOR by reasoning over:
              - The organizer's sensitivity (call getContactSensitivity if you haven't
                this run) — a TOUCHY organizer needs an AIRTIGHT-tier excuse;
                EASYGOING tolerates a CREDIBLE one.
              - The known attendees — an "out of town" or "stuck elsewhere" flavor
                contradicts a known attendee who would have just seen Baruch that day.
              - Which flavors remain available (full ExcuseFlavor enum minus
                BARUCH_CLASSIC minus recently used). Pick the one that best fits
                THIS organizer and THIS attendee list — not a fixed default.

            Then draft a messageToOrganizer (two+ sentences, no placeholders) and a
            short hallwayScript consistent with the chosen flavor.
            """.trimIndent()
        }

        val chatReply by subgraphWithTask<String, String>(
            tools = userTools,  // can ping Baruch if needed; no calendar / organizer / write
            name = "chatReply",
            llmModel = AnthropicModels.Sonnet_4_5,
        ) { input ->
            """
            Today's date: 2026-05-26.

            Answer Baruch's question or chat with him. Do NOT take any excuse-related
            actions — no calendar reads, no sendExcuse, no new excuse.

            About your memory: the prior-excuse facts retrieved from LongTermMemory
            describe past events — each is dated in its content. They are NOT today's
            task. When Baruch asks "what did we use last year" or "did you tell
            <organizer>...", consult those retrieved facts. If he asks about an event
            that doesn't appear in the retrieved facts, say so plainly — don't invent.

            User said: $input
            """.trimIndent()
        }

        edge(nodeStart forwardTo classify)

        // Excuse branch — when intent classifies as an excuse request.
        edge(classify forwardTo identifyExcuse onCondition { it: ClassifiedInput -> it.intent == IntentClassification.EXCUSE_REQUEST } transformed { it.userMessage })
        edge(identifyExcuse forwardTo deployExcuse)
        edge(deployExcuse forwardTo verifyExcuse)
        edge(verifyExcuse forwardTo nodeFinish onCondition { it: CriticResult<ExcuseDeployment> -> it.successful } transformed { JclawResult.ExcuseSent(it.input) })
        edge(verifyExcuse forwardTo refineExcuse onCondition { it: CriticResult<ExcuseDeployment> -> !it.successful } transformed { it.feedback ?: "(no feedback)" })
        edge(refineExcuse forwardTo verifyExcuse)

        // Chat branch — when intent classifies as anything else.
        edge(classify forwardTo chatReply onCondition { it: ClassifiedInput -> it.intent == IntentClassification.CHAT } transformed { it.userMessage })
        edge(chatReply forwardTo nodeFinish transformed { reply: String -> JclawResult.ChatReply(reply) })
    }
