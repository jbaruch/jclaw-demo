package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import jclaw.Observability.langfuse
import jclaw.domain.Scenario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Gemini gathers context, Claude drafts/refines, Codex judges. The app owns sending. */
fun main(): Unit = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val naive = System.getenv("JCLAW_NAIVE") == "1"
    val autoSend = System.getenv("JCLAW_AUTOSEND") == "1"
    Mcp.boot("calendar-mcp", "organizer-mcp").use { mcp ->
        val skills = AgentSkills.discover()
        val memory = Memory.open(LLMEmbedder(GoogleLLMClient(apiKey), GoogleModels.Embeddings.GeminiEmbedding001))
        println("[mode] " + if (naive) "NAIVE - less context and no memory" else "DOMAIN-MODELLED")
        println("[models] ${Models.flash.id} identifies; Claude subscription drafts/refines; Codex subscription judges")
        val agent = AIAgent(
            id = "j-claw",
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            agentConfig = AIAgentConfig.withSystemPrompt(
                prompt = "${Persona.PROMPT}\n${skills.prompt}", llm = Models.flash, maxAgentIterations = 200,
            ),
            strategy = jclawStrategy(
                mcp, naive, skills,
                onStage = { stage, model, state -> println("[$stage] $model - $state") },
                onVerdict = ::println,
            ),
            toolRegistry = mcp.registry + skills.registry,
        ) {
            if (Observability.enabled) install(OpenTelemetry) {
                langfuse(
                    4, if (naive) "naive" else "domain-modelled", "critic:codex", "drafter:claude-code",
                    metadata = mapOf("model" to Models.flash.id, "drafter" to "claude-code", "critic" to "codex"),
                )
            }
            if (!naive) install(LongTermMemory) {
                retrieval { storage = memory; searchStrategy = SimilaritySearchStrategy(topK = 5) }
            }
            handleEvents { onToolCallStarting { println("      tool ${it.toolName}") } }
        }
        try {
            println("\n${Persona.WELCOME} Blank line or ctrl-D quits.\n")
            while (true) {
                print("you: ")
                val line = readlnOrNull()?.trim()
                if (line.isNullOrEmpty()) break
                var deliveryAttempted = false
                try {
                    val result = agent.run(line)
                    when (result) {
                        is JclawResult.ChatReply -> println("j-claw: ${result.text}")
                        is JclawResult.Blocked -> println("BLOCKED: ${result.reason}\nNothing was sent. There is no send override.")
                        is JclawResult.ReadyToSend -> {
                            val plan = result.deployment
                            println("=== CODEX APPROVED THIS PLAN ===")
                            println("flavor: ${plan.flavor}\nmessage: ${plan.messageToOrganizer}\nhallway: ${plan.hallwayScript}")
                            val sent = deliverApproved(result,
                                confirm = {
                                    print("Send it? [y/N] ")
                                    if (autoSend) { println("y (mock rehearsal)"); true }
                                    else readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
                                },
                                send = {
                                    deliveryAttempted = true
                                    val receipt = mcp.call("organizer-mcp", "sendDecline",
                                        mapOf("eventId" to Scenario.EVENT_ID, "message" to it.messageToOrganizer))
                                    println("sent: $receipt")
                                    try {
                                        memory.add(listOf(Memory.story(Scenario.EVENT_TITLE, Scenario.ORGANIZER, it.flavor.name, it.messageToOrganizer)))
                                    } catch (error: Exception) {
                                        println("Delivered, but could not save to memory: ${error.message}")
                                    }
                                },
                            )
                            if (!sent) println("held. Nothing was sent.")
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (deliveryAttempted) println("Delivery attempt failed: ${error.message}. Check the organizer receipt before retrying.")
                    else println("BLOCKED: ${error.message}\nNothing was sent.")
                }
                println()
            }
        } finally {
            agent.close()
            Observability.flush()
            mcp.close()
        }
        exitProcess(0)
    }
}
