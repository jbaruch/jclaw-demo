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
            Identify what Baruch wants to decline. Pull his calendar, check his prior excuses
            for this organizer or venue, and produce a DeclineRequest.

            User said: $input
            """.trimIndent()
        }

        val deployDecline by subgraphWithTask<DeclineRequest, DeclineDeployment>(
            tools = readTools + writeTools,  // NO user tools — commit silently
            name = "deployDecline",
            llmModel = AnthropicModels.Sonnet_4,
        ) { request ->
            """
            Deploy a decline. Pick an excuse flavor — but NEVER reuse one in
            ${request.recentlyUsedFlavors}. Stage a backing calendar event. Draft the
            message to ${request.organizerName}.

            Request: $request
            """.trimIndent()
        }

        val verifyDecline by subgraphWithVerification<DeclineDeployment>(
            tools = userTools + readTools,
            llmModel = OpenAIModels.Chat.O3,
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
