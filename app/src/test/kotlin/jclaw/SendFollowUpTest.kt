package jclaw

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jclaw.domain.DeclineCritique
import jclaw.domain.DeclineDeployment
import jclaw.domain.DeclineRequest
import jclaw.domain.ExcuseFlavor
import jclaw.domain.PlausibilityTier
import jclaw.domain.Scenario
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

class SendFollowUpTest : StringSpec({
    "only exact affirmative replies authorize sending" {
        listOf("send", "SEND", " y ", "yes").forEach { sendReply(it) shouldBe SendReply.Send }
        listOf("", "n", "no", "Hold", "no thanks.", "don't send", "cancel").forEach {
            sendReply(it) shouldBe SendReply.Hold
        }
        listOf("no, let's use another one", "rewrite in corporate-speak", "yes, but change the reason", "send it after editing", "don't send that, try a different approach").forEach {
            sendReply(it) shouldBe SendReply.FollowUp(it)
        }
    }

    "confirmation preserves substantive replies as next requests without ever sending the held plan" {
        val plan = DeclineDeployment(ExcuseFlavor.ALREADY_PROFICIENT, messageToOrganizer = "Original draft", hallwayScript = "Original script")
        listOf("no, let's use another one", "rewrite in corporate-speak", "yes, but change the reason", "no").forEach { answer ->
            val conversation = Conversation("Assistant")
            val queued = mutableListOf<String>()
            deliverApproved(
                JclawResult.ReadyToSend(plan),
                confirm = { conversation.confirmSend(answer) { queued += it } },
                send = { error("A hold or follow-up must not send") },
            ) shouldBe false
            queued shouldBe if (answer == "no") emptyList() else listOf(answer)
        }
    }

    "native graph preserves revision intent and context through typed handoffs with a fresh review budget" {
        val followUp = "no, let's use another one"
        val style = "rewrite in corporate-speak"
        val original = DeclineDeployment(ExcuseFlavor.ALREADY_PROFICIENT, messageToOrganizer = "I already teach this subject.", hallwayScript = "I teach it.")
        val alternative = original.copy(flavor = ExcuseFlavor.DEADLINE, messageToOrganizer = "An alternative proposal.")
        val request = DeclineRequest(Scenario.EVENT_ID, Scenario.BURNED, Scenario.ATTENDEES, Scenario.ORGANIZER)
        val identifiedRevision = request.copy(userInstruction = "incomplete model echo", previouslyProposedFlavors = listOf(original.flavor))
        val prompts = mutableListOf<Prompt>()
        val draftRequests = mutableListOf<DeclineRequest>()
        val refinements = mutableListOf<String>()
        val events = mutableListOf<ReviewEvent>()
        val conversation = Conversation("Generic assistant")
        val results: List<JsonElement> = listOf(
            Json.encodeToJsonElement(ClassifiedInput(Intent.EXCUSE_REQUEST, "Plan a decline")), Json.encodeToJsonElement(request),
            Json.encodeToJsonElement(ClassifiedInput(Intent.EXCUSE_REQUEST, "another")), Json.encodeToJsonElement(identifiedRevision),
            Json.encodeToJsonElement(ClassifiedInput(Intent.CHAT, style)), JsonPrimitive("A corporate rewrite of the preceding draft."),
            Json.encodeToJsonElement(ClassifiedInput(Intent.EXCUSE_REQUEST, "Try a new plan")), Json.encodeToJsonElement(identifiedRevision),
        )
        val agent = AIAgent(
            promptExecutor = ScriptedExecutor { prompt, tools ->
                prompts += prompt
                val tool = tools.single { it.name == "finalize_task_result" }
                val result = results[prompts.lastIndex]
                Message.Assistant(MessagePart.Tool.Call("finish-${prompts.size}", tool.name,
                    JsonObject(mapOf("result" to result)).toString()), ResponseMetaInfo.Empty)
            },
            llmModel = Models.flash, systemPrompt = conversation.systemPrompt, maxIterations = 200,
            strategy = jclawStrategy(
                readTools = emptyList(), naive = true,
                draft = {
                    draftRequests += it
                    if (draftRequests.size == 1) original else alternative
                },
                refinePlan = { refinements += it; alternative },
                judgePlan = { plan ->
                    DeclineCritique(PlausibilityTier.CREDIBLE, plan == original,
                        if (plan == original) "Fixture approval" else "Fixture rejection")
                },
                onReview = { events += it },
            ),
        ) { install(ChatMemory) { conversation.configure(this) } }
        try {
            val ready = conversation.run(agent, "Plan a decline")
            ready shouldBe JclawResult.ReadyToSend(original)
            var queued: String? = null
            deliverApproved(ready, confirm = { conversation.confirmSend(followUp) { queued = it } },
                send = { error("The previous approved draft must be held") }) shouldBe false
            conversation.assistant("Held. Nothing was sent.")
            conversation.run(agent, requireNotNull(queued)) shouldBe JclawResult.Blocked(
                "Codex rejected the plan after 2 refinements: Fixture rejection", alternative,
            )
            draftRequests[1].userInstruction shouldBe followUp
            draftRequests[1].recentlyUsedFlavors shouldBe Scenario.BURNED
            draftRequests[1].previouslyProposedFlavors shouldBe listOf(original.flavor)
            CliCritic.claudeDraftRequest(draftRequests[1]) shouldContain followUp
            refinements.size shouldBe 2
            refinements.forEach { it shouldContain followUp; it shouldContain "previouslyProposedFlavors=[ALREADY_PROFICIENT]" }
            val classifierHistory = prompts[2].messages
            classifierHistory.filterIsInstance<Message.User>().count { it.textContent() == followUp } shouldBe 1
            classifierHistory.joinToString { it.textContent() } shouldContain original.messageToOrganizer
            prompts[3].messages.joinToString { it.textContent() } shouldContain "USER INSTRUCTION:\n$followUp"
            val beforeStyle = events.size
            conversation.run(agent, style) shouldBe JclawResult.ChatReply("A corporate rewrite of the preceding draft.")
            events.size shouldBe beforeStyle
            draftRequests.size shouldBe 2
            prompts[5].messages.joinToString { it.textContent() } shouldContain alternative.messageToOrganizer
            Memory.sentDeclines.extract(prompts[5].messages).size shouldBe 0
            conversation.run(agent, "Try a new plan")
            events.filterIsInstance<ReviewEvent.Draft>().map { it.attempt.refinements } shouldBe listOf(0, 0, 1, 2, 0, 1, 2)
        } finally { agent.close() }
    }
})
