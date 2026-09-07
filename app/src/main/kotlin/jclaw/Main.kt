package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import jclaw.domain.Scenario
import kotlinx.coroutines.runBlocking

/**
 * ROUND 2 - tools and MCP.
 *
 * The agent can now act: it reads the calendar, checks how touchy the
 * organizer is, stages a backing event, and sends the decline for real.
 *
 * What it CANNOT do is remember. The calendar records that Baruch bailed on
 * three sessions; it does not record what he told Dana each time. So j-claw
 * reaches for the most natural excuse in the world - and it is the same one
 * he has already used on her twice.
 */
fun main() = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val (tools, procs) = Mcp.registry("calendar-mcp", "organizer-mcp")

    try {
        println("tools discovered: " + tools.tools.joinToString { it.name })

        val jclaw = AIAgent(
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            systemPrompt = Scenario.SYSTEM_PROMPT,
            llmModel = GoogleModels.Gemini3_5Flash,
            toolRegistry = tools,
        ) {
            handleEvents {
                onToolCallStarting { println("  -> ${it.toolName}(${it.toolArgs})") }
            }
        }

        val task = """
            Get me out of "${Scenario.EVENT_TITLE}" (event id ${Scenario.EVENT_ID}),
            run by ${Scenario.ORGANIZER}.
            Stage a calendar event that makes the excuse hold up, then send the decline.
            Tell me what you sent and what I should say if Dana asks me about it tomorrow.
        """.trimIndent()

        println("\n> $task\n")
        println(jclaw.run(task))
    } finally {
        procs.forEach { it.destroy() }
    }
}
