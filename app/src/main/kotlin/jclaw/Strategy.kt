package jclaw

import ai.koog.agents.cli.asNode
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.agents.ext.agent.subgraphWithVerification
import ai.koog.prompt.executor.clients.google.GoogleModels
import jclaw.domain.DeclineDeployment
import jclaw.domain.DeclineRequest
import jclaw.domain.Scenario
import java.util.concurrent.atomic.AtomicInteger

/**
 * The j-claw pipeline, shared by the stdout build (Main) and the TUI build (Tui).
 *
 *   identify --> deploy --> verify --(approved)--> done
 *                  ^           |
 *                  +-- refine <+  (rejected, with feedback)
 *
 * @param naive     strip the typed constraint out of the handoff so the critic has
 *                  something to catch - the A/B that makes the argument visible
 * @param cliCritic run the critic on Claude Code via CliAIAgent instead of Gemini
 */
fun jclawStrategy(
    mcp: Mcp,
    naive: Boolean,
    cliCritic: Boolean,
): AIAgentGraphStrategy<String, DeclineDeployment> {
    val slices = Slices(mcp.registry)
    val context = if (naive) "" else Scenario.USER_CONTEXT + "\n"

    // A critic that can reject forever is a hang, not a safety feature.
    val refusals = AtomicInteger(0)
    val maxRefusals = 2

    // Claude's critique tells us yes or no, but not what it was judging, so we
    // hold onto the last plan on its way in.
    var lastPlan: DeclineDeployment? = null

    val jclawStrategy = strategy<String, DeclineDeployment>("j-claw") {

        val identify by subgraphWithTask<String, DeclineRequest>(
            tools = slices.read,
            llmModel = GoogleModels.Gemini3_5Flash,
        ) { input ->
            if (naive) "Work out what Baruch is trying to get out of and who runs it.\n$input"
            else "Work out exactly what Baruch is trying to get out of, who runs it, who would " +
                "notice, and which excuse flavors are already burned with those people.\n" +
                "$context$input"
        }

        val deploy by subgraphWithTask<DeclineRequest, DeclineDeployment>(
            tools = slices.read + slices.write,
            llmModel = GoogleModels.Gemini3_5Flash,
        ) { request ->
            "Pick the excuse flavor most likely to work, stage a calendar event that covers " +
                "the session time, draft the message, and write the hallway script. " +
                "The message you write MUST be the flavor you selected - do not label it one " +
                "thing and write another. You cannot contact anyone - just produce the plan.\n" +
                "$context$request"
        }

        val verifyByClaude by CliCritic.claude().asNode("verify-claude")

        val verify by subgraphWithVerification<DeclineDeployment>(
            tools = slices.read,
            llmModel = GoogleModels.Gemini3_1Pro_Preview,
        ) { deployment ->
            "You are a hostile reviewer. Reject this plan if the flavor is already burned, " +
                "if the message does not actually match the flavor it claims, if the staged " +
                "calendar event does not cover the session time, or if a TOUCHY organizer " +
                "would check and catch it. Prefer excuses that are literally true, because " +
                "those survive any amount of checking. Be specific about what to fix.\n" +
                "Already burned with this organizer: ${Scenario.BURNED.joinToString()}.\n" +
                "${Scenario.USER_CONTEXT}\n$deployment"
        }

        val refine by subgraphWithTask<String, DeclineDeployment>(
            tools = slices.read + slices.write,
            llmModel = GoogleModels.Gemini3_1Pro_Preview,
        ) { feedback ->
            "The reviewer rejected the plan. Fix exactly what they objected to, nothing else.\n$feedback"
        }

        edge(nodeStart forwardTo identify)
        edge(identify forwardTo deploy)

        if (cliCritic) {
            edge(deploy forwardTo verifyByClaude transformed { lastPlan = it; it })
            edge(
                verifyByClaude forwardTo nodeFinish
                    onCondition { it.structuredResult?.approved == true }
                    transformed { lastPlan!! }
            )
            edge(
                verifyByClaude forwardTo refine
                    onCondition { it.structuredResult?.approved != true && refusals.incrementAndGet() <= maxRefusals }
                    transformed { c -> "Tier: ${c.structuredResult?.tier}. ${c.structuredResult?.feedback ?: "critic returned nothing parseable"}" }
            )
            edge(
                verifyByClaude forwardTo nodeFinish
                    onCondition { it.structuredResult?.approved != true }
                    transformed {
                        println("      claude still unhappy after $maxRefusals refinements - shipping last draft")
                        lastPlan!!
                    }
            )
            edge(refine forwardTo verifyByClaude)
        } else {
        edge(deploy forwardTo verify)
        edge(verify forwardTo nodeFinish onCondition { it.successful } transformed { it.input })
        edge(
            verify forwardTo refine
                onCondition { !it.successful && refusals.incrementAndGet() <= maxRefusals }
                transformed { it.feedback }
        )
        // Out of retries: ship the last draft rather than loop forever, and say so.
        edge(
            verify forwardTo nodeFinish
                onCondition { !it.successful }
                transformed {
                    println("      critic still unhappy after $maxRefusals refinements - shipping last draft")
                    it.input
                }
        )
        edge(refine forwardTo verify)
        }
    }

    return jclawStrategy
}
