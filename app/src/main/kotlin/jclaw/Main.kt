package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import jclaw.Observability.langfuse
import ai.koog.agents.longtermmemory.feature.FailurePolicy
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Normal chat with MCP tools, persistent memory, and runtime skills. */
fun main(): Unit = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val skills = AgentSkills.discover()
    val (tools, procs) = Mcp.registry("calendar-mcp", "organizer-mcp")

    // Memory is a directory on disk. memory/documents/ is previously sent messages:
    // three committed files today, one more after each confirmed send. Nothing is seeded in code.
    val memory = Memory.open(LLMEmbedder(GoogleLLMClient(apiKey), GoogleModels.Embeddings.GeminiEmbedding001))

    try {
        val jclaw = AIAgent(
            id = "j-claw",   // names the agent spans in Langfuse; a UUID otherwise
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            systemPrompt = "${Persona.PROMPT}\n${skills.prompt}",
            llmModel = Models.flash,
            toolRegistry = tools + skills.registry,
        ) {
            install(LongTermMemory) {
                retrieval {
                    storage = memory
                    searchStrategy = SimilaritySearchStrategy(topK = 5)
                }
                ingestion {
                    storage = memory
                    documentExtractor = Memory.sentDeclines
                    failurePolicy = FailurePolicy.FAIL_FAST
                }
            }
            if (Observability.enabled) install(OpenTelemetry) {
                langfuse(3, "memory", "skills", metadata = mapOf("model" to Models.flash.id))
            }
            handleEvents {
                onToolCallStarting { println("  -> ${it.toolName}(${it.toolArgs})") }
            }
        }

        println()
        println("${Persona.WELCOME} (blank line or ctrl-D to quit)")
        println()

        while (true) {
            print("you: ")
            val line = readlnOrNull()?.trim()
            if (line.isNullOrEmpty()) break
            println()
            println("j-claw: " + jclaw.run(line))
            println()
        }
        // Closing ends the spans Koog still holds; the flush ships them (see Observability).
        jclaw.close()
        Observability.flush()
    } finally {
        procs.forEach { it.destroyForcibly() }
        procs.forEach { runCatching { it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) } }
        // MCP's stdio transport leaves a non-daemon reader thread alive.
        exitProcess(0)
    }
}
