package com.jbaruch.jclaw.koog

import ai.koog.agents.core.agent.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.onCondition
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.agents.ext.agent.subgraphWithVerification
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels

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
            llmModel = OpenAIModels.Chat.GPT4o, // cheap classification tier
        ) { input ->
            """
            Identify what Baruch wants to decline. Pull his calendar, check his prior excuses
            for this organizer or venue, and produce a DeclineRequest.

            User said: $input
            """.trimIndent()
        }

        val deployDecline by subgraphWithTask<DeclineRequest, DeclineDeployment>(
            tools = readTools + writeTools,  // NO user tools — commit silently
            name = "deployDecline",
            llmModel = AnthropicModels.Sonnet_4_5,  // mid-tier action
        ) { request ->
            """
            Deploy a decline. Pick an excuse flavor — but NEVER reuse one in
            ${request.recentlyUsedFlavors}. Stage a backing calendar event. Draft the
            message to ${request.organizerName}.

            Request: $request
            """.trimIndent()
        }

        val verifyDecline by subgraphWithVerification<DeclineDeployment>(
            tools = userTools + readTools,  // can ask Baruch — cannot mutate further
            llmModel = OpenAIModels.Chat.O3,  // reasoning tier
        ) { deployment ->
            """
            Pressure-test this decline. Would it survive contact with the organizer the next day?
            The PlausibilityTier.JENNY_FROM_THE_BLOCK tier means the excuse failed.

            Deployment: $deployment
            """.trimIndent()
        }

        val refineDecline by subgraphWithTask<String, DeclineDeployment>(
            tools = readTools + writeTools,
            name = "refineDecline",
            llmModel = AnthropicModels.Sonnet_4_5,
        ) { feedback ->
            """
            Tighten the decline. The previous attempt failed verification with this feedback:
            $feedback
            """.trimIndent()
        }

        edge(nodeStart forwardTo identifyDecline)
        edge(identifyDecline forwardTo deployDecline)
        edge(deployDecline forwardTo verifyDecline)
        edge(verifyDecline forwardTo nodeFinish onCondition { it.successful } transformed { it.input })
        edge(verifyDecline forwardTo refineDecline onCondition { !it.successful } transformed { it.feedback ?: "(no feedback)" })
        edge(refineDecline forwardTo verifyDecline)
    }
