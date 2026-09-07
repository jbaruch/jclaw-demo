package jclaw

import ai.koog.agents.cli.asNode
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.agents.ext.agent.subgraphWithVerification
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import ai.koog.agents.longtermmemory.storage.InMemoryRecordStorage
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import jclaw.domain.DeclineDeployment
import jclaw.domain.DeclineRequest
import jclaw.domain.Scenario
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * ROUND 4 - the domain-modelled pipeline.
 *
 * Rounds 1-3 were one agent having one conversation. This is three typed
 * subtasks handing each other DATA, with a critic in the loop:
 *
 *   identify -> deploy -> verify --(approved)--> done
 *                  ^                 |
 *                  +----- refine <---+ (rejected, with feedback)
 *
 * Each phase gets its own model and its own slice of tools. `deploy` has no
 * way to reach a human at all. And the critic is not the phase that wrote the
 * plan - you do not let the model that drafted the excuse grade it.
 *
 * The send is deliberately NOT in the graph. It is the one irreversible act
 * here, so the application performs it, after a human says yes.
 */
fun main() = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }

    Mcp.boot("calendar-mcp", "organizer-mcp").use { mcp ->
        val slices = Slices(mcp.registry)
        val memory = InMemoryRecordStorage().apply { add(PriorExcuses.seed()) }

        // JCLAW_NAIVE=1 strips the typed constraint out of the handoff: identify stops
        // reporting which flavors are burned, and nobody tells deploy about Baruch's day
        // job. The pipeline shape is identical. Only the DATA is poorer - which is the
        // entire argument, and you can watch the critic start earning its keep.
        val naive = System.getenv("JCLAW_NAIVE") == "1"
        // JCLAW_CRITIC=cli hands the review to Claude Code on Baruch's subscription,
        // via Koog 1.1.1's CliAIAgent. Gemini drafts, Claude judges. Different vendor,
        // different weights - and the handoff between them is a typed data class.
        val cliCritic = System.getenv("JCLAW_CRITIC") == "cli"
        val context = if (naive) "" else Scenario.USER_CONTEXT + "\n"
        println(if (naive) "[mode] NAIVE - typed constraint removed from the handoff"
                else       "[mode] DOMAIN-MODELLED - constraint travels in the typed handoff")
        println(if (cliCritic) "[critic] Claude Code via CliAIAgent (subscription, no API key)"
                else           "[critic] Gemini 3.1 Pro")

        val jclawStrategy = jclawStrategy(mcp, naive, cliCritic)

        val jclaw = AIAgent(
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            agentConfig = AIAgentConfig.withSystemPrompt(
                prompt = Scenario.SYSTEM_PROMPT,
                llm = GoogleModels.Gemini3_5Flash,
                maxAgentIterations = 200,
            ),
            strategy = jclawStrategy,
            toolRegistry = mcp.registry,
        ) {
            if (!naive) install(LongTermMemory) {
                retrieval {
                    storage = memory
                    searchStrategy = SimilaritySearchStrategy(topK = 5)
                }
            }
            handleEvents {
                onToolCallStarting { println("      tool  ${it.toolName}") }
            }
        }

        val task = "Get me out of \"${Scenario.EVENT_TITLE}\" (event id ${Scenario.EVENT_ID}), " +
            "run by ${Scenario.ORGANIZER}."
        println("\n> $task\n")

        val plan = jclaw.run(task)

        println("\n=== THE CRITIC APPROVED THIS ===")
        println("flavor:  ${plan.flavor}")
        println("alibi:   ${plan.fakeCalendarEventId}")
        println("message: ${plan.messageToOrganizer}")
        println("hallway: ${plan.hallwayScript}")

        // The one irreversible act. A human authorises it; the application performs it.
        val autoSend = System.getenv("JCLAW_AUTOSEND") == "1"
        print("\nSend it? [y/N] ")
        val ok = if (autoSend) true.also { println("y (JCLAW_AUTOSEND)") }
                 else readlnOrNull()?.trim()?.lowercase() == "y"

        if (ok) {
            val receipt = mcp.call(
                server = "organizer-mcp",
                tool = "sendDecline",
                args = mapOf("eventId" to Scenario.EVENT_ID, "message" to plan.messageToOrganizer),
            )
            println("sent: $receipt")
        } else {
            println("held. nothing was sent.")
        }
    }
}
