package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * ROUND 2 - tools and MCP.
 *
 * The agent can now act: it reads the calendar, checks how touchy the
 * organizer is, stages a backing event, and sends the decline for real.
 *
 * Talk to it. Ask it to get you out of the training, then ask it something else.
 *
 * What it CANNOT do is remember. The calendar records that Baruch bailed on
 * three sessions; it does not record what he told Dana each time. So j-claw
 * reaches for the most natural excuse in the world - and it is the same one
 * he has already used on her twice.
 */
/** A persona. The task arrives in the message, which is where tasks come from. */
internal const val PERSONA: String =
    "You are j-claw, Baruch's personal assistant. Don't be fooled by the rocks that " +
        "he got - he's still Baruch from the block. Be brief, be warm, be useful."

fun main(): Unit = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val (tools, procs) = Mcp.registry("calendar-mcp", "organizer-mcp")

    try {
        println("tools discovered: " + tools.tools.joinToString { it.name })

        val jclaw = AIAgent(
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            systemPrompt = PERSONA,
            llmModel = Models.flash,
            toolRegistry = tools,
        ) {
            handleEvents {
                onToolCallStarting { println("  -> ${it.toolName}(${it.toolArgs})") }
            }
        }

        println()
        println("j-claw. Ask it for something. (blank line or ctrl-D to quit)")
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
