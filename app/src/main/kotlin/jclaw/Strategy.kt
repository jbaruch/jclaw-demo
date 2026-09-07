package jclaw

import ai.koog.agents.cli.asNode
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.agents.ext.agent.subgraphWithVerification
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
    userTools: UserTools? = null,
): AIAgentGraphStrategy<String, JclawResult> {
    val slices = Slices(mcp.registry, userTools)
    val context = if (naive) "" else Scenario.USER_CONTEXT + "\n"

    // A critic that can reject forever is a hang, not a safety feature.
    val refusals = AtomicInteger(0)
    val maxRefusals = 2

    // Claude's critique tells us yes or no, but not what it was judging, so we
    // hold onto the last plan on its way in.
    var lastPlan: DeclineDeployment? = null

    val jclawStrategy = strategy<String, JclawResult>("j-claw") {

            val classify by subgraphWithTask<String, ClassifiedInput>(
                tools = emptyList(),
                llmModel = Models.flash,
            ) { input ->
                "Decide whether Baruch wants out of an obligation (EXCUSE_REQUEST) or is " +
                    "just talking (CHAT). Echo his message verbatim into userMessage.\n$input"
            }

            val chatReply by subgraphWithTask<String, String>(
                tools = slices.read,
                llmModel = Models.flash,
            ) { input -> "Reply to Baruch, briefly and in character.\n$input" }


        val identify by subgraphWithTask<String, DeclineRequest>(
            tools = slices.read,
            llmModel = Models.flash,
        ) { input ->
            if (naive) "Work out what Baruch is trying to get out of and who runs it.\n$input"
            else "Work out exactly what Baruch is trying to get out of, who runs it, who would " +
                "notice, and which excuse flavors are already burned with those people.\n" +
                "$context$input"
        }

        val deploy by subgraphWithTask<DeclineRequest, DeclineDeployment>(
            tools = slices.read + slices.write,
            llmModel = Models.flash,
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
            llmModel = Models.pro,
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
            llmModel = Models.pro,
        ) { feedback ->
            "The reviewer rejected the plan. Fix exactly what they objected to, nothing else.\n$feedback"
        }

        // The critic can ask questions with its user tools, but approval is not left
        // to whether a model decides to call one. It is a node, so it always happens.
        val approve by node<DeclineDeployment, DeclineDeployment> { plan ->
            val verdict = userTools?.awaitApproval(
                "flavor ${plan.flavor} - \"${plan.messageToOrganizer.take(90)}...\""
            ) ?: "APPROVED"
            if (!verdict.startsWith("APPROVED")) println("      you rejected it: $verdict")
            plan
        }

        val approveUnverified by node<DeclineDeployment, DeclineDeployment> { plan ->
            val verdict = userTools?.awaitApproval(
                "CRITIC REJECTED THIS - flavor ${plan.flavor} - \"${plan.messageToOrganizer.take(80)}...\""
            ) ?: "APPROVED"
            if (!verdict.startsWith("APPROVED")) println("      you rejected it too: $verdict")
            plan
        }

        // Both approval nodes exit the same way, whichever critic ran.
        edge(approve forwardTo nodeFinish transformed { JclawResult.ExcuseSent(it, criticApproved = true) })
        edge(approveUnverified forwardTo nodeFinish transformed { JclawResult.ExcuseSent(it, criticApproved = false) })

        edge(nodeStart forwardTo classify)
        edge(
            classify forwardTo identify
                onCondition { it.intent == Intent.EXCUSE_REQUEST }
                transformed { it.userMessage }
        )
        edge(
            classify forwardTo chatReply
                onCondition { it.intent == Intent.CHAT }
                transformed { it.userMessage }
        )
        edge(chatReply forwardTo nodeFinish transformed { JclawResult.ChatReply(it) })
        edge(identify forwardTo deploy)

        if (cliCritic) {
            edge(deploy forwardTo verifyByClaude transformed { lastPlan = it; it })
            edge(
                verifyByClaude forwardTo approve
                    onCondition { it.structuredResult?.approved == true }
                    transformed { lastPlan!! }
            )
            edge(
                verifyByClaude forwardTo refine
                    onCondition { it.structuredResult?.approved != true && refusals.incrementAndGet() <= maxRefusals }
                    transformed { c ->
                        val fb = "Tier: ${c.structuredResult?.tier}. " +
                            (c.structuredResult?.feedback ?: "critic returned nothing parseable")
                        println("      critic says: $fb")
                        fb
                    }
            )
            // Out of retries. Still goes past a human - an unapproved draft is exactly
            // the one someone should look at.
            edge(
                verifyByClaude forwardTo approveUnverified
                    onCondition { it.structuredResult?.approved != true }
                    transformed {
                        println("      claude still unhappy after $maxRefusals refinements - shipping last draft")
                        lastPlan!!
                    }
            )
            edge(refine forwardTo verifyByClaude)
        } else {
        edge(deploy forwardTo verify)
        edge(verify forwardTo approve onCondition { it.successful } transformed { it.input })
        edge(
            verify forwardTo refine
                onCondition { !it.successful && refusals.incrementAndGet() <= maxRefusals }
                transformed { println("      critic says: ${it.feedback}"); it.feedback }
        )
        // Out of retries: ship the last draft rather than loop forever, and say so.
        // Out of retries. Still goes past a human.
        edge(
            verify forwardTo approveUnverified
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
