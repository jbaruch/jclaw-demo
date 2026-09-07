package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.storage.InMemoryRecordStorage
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * ROUND 3 - memory.
 *
 * Same tools, same MCP servers, same task as round 2. The only difference is
 * that j-claw now remembers what it told Dana the last three times. The
 * calendar knew he bailed; memory knows the story he used.
 */
/** A persona. The task arrives in the message, which is where tasks come from. */
private const val PERSONA: String =
    "You are j-claw, Baruch's personal assistant. Don't be fooled by the rocks that " +
        "he got - he's still Baruch from the block. Be brief, be warm, be useful."

fun main(): Unit = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val (tools, procs) = Mcp.registry("calendar-mcp", "organizer-mcp")

    // Pre-seeded so the very first run has something to avoid.
    val memory = InMemoryRecordStorage()
    memory.add(PriorExcuses.seed())

    try {
        val jclaw = AIAgent(
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            systemPrompt = PERSONA,
            llmModel = Models.flash,
            toolRegistry = tools,
        ) {
            install(LongTermMemory) {
                retrieval {
                    storage = memory
                    searchStrategy = SimilaritySearchStrategy(topK = 5)
                }
            }
            handleEvents {
                onToolCallStarting { println("  -> ${it.toolName}(${it.toolArgs})") }
            }
        }

        println()
        println("j-claw, with a memory this time. (blank line or ctrl-D to quit)")
        println()

        while (true) {
            print("you: ")
            val line = readlnOrNull()?.trim()
            if (line.isNullOrEmpty()) break
            println()
            println("j-claw: " + jclaw.run(line))
            println()
        }
    } finally {
        procs.forEach { it.destroyForcibly() }
        procs.forEach { runCatching { it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) } }
        // MCP's stdio transport leaves a non-daemon reader thread alive.
        exitProcess(0)
    }
}
