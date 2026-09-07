package jclaw.round3

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.storage.InMemoryRecordStorage
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import ai.koog.rag.base.TextDocument
import jclaw.domain.Scenario
import kotlinx.coroutines.runBlocking

/**
 * ROUND 3 - memory.
 *
 * Same tools, same MCP servers, same task as round 2. The only difference is
 * that j-claw now remembers what it told Dana the last three times. The
 * calendar knew he bailed; memory knows the story he used.
 */
fun main() = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val (tools, procs) = Mcp.registry("calendar-mcp", "organizer-mcp")

    // Pre-seeded so the very first run has something to avoid.
    val memory = InMemoryRecordStorage()
    memory.add(PriorExcuses.seed())

    try {
        val jclaw = AIAgent(
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            systemPrompt = Scenario.SYSTEM_PROMPT,
            llmModel = GoogleModels.Gemini3_5Flash,
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

        val task = """
            Get me out of "${Scenario.EVENT_TITLE}" (event id ${Scenario.EVENT_ID}),
            run by ${Scenario.ORGANIZER}.
            Do not reuse an excuse I have already used on her.
            Stage a calendar event that makes it hold up, then send the decline.
        """.trimIndent()

        println("\n> $task\n")
        println(jclaw.run(task))
    } finally {
        procs.forEach { it.destroy() }
    }
}
