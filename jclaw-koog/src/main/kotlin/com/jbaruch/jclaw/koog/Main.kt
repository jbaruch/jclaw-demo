package com.jbaruch.jclaw.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.fromProcess
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * j-claw — Round 4 (full pipeline) entry point.
 *
 * Layout on main:
 *   - Two mock MCP servers (calendar-mcp, organizer-mcp) launched as subprocesses
 *   - Three sliced ToolSets (UserTools = local, ReadTools = local + MCP, WriteTools = MCP)
 *   - In-process pre-seeded "memory" via SeedMemory.priorDeclines
 *   - Four-phase strategy from Strategy.kt
 *
 * Required env: OPENAI_API_KEY, ANTHROPIC_API_KEY.
 */
fun main(args: Array<String>) = runBlocking {
    val openAiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Set OPENAI_API_KEY")
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
        ?: error("Set ANTHROPIC_API_KEY")

    // ----- MCP servers (launched as subprocesses; tools come back via stdio) -----
    val calendarRegistry = McpToolRegistryProvider.fromProcess(
        ProcessBuilder("./mocks/calendar-mcp/build/install/calendar-mcp/bin/calendar-mcp").start()
    )
    val organizerRegistry = McpToolRegistryProvider.fromProcess(
        ProcessBuilder("./mocks/organizer-mcp/build/install/organizer-mcp/bin/organizer-mcp").start()
    )

    // ----- Local tools -----
    val chatOutbound: (String) -> Unit = { println(it) }
    val reactions = Channel<String>(capacity = 16)

    val userTools = UserTools(outbound = chatOutbound, reactions = reactions)
    val readTools = ReadTools(priorDeclines = SeedMemory.priorDeclines)

    val localRegistry = ToolRegistry {
        tools(userTools.asTools())
        tools(readTools.asTools())
    }

    val toolRegistry = localRegistry + calendarRegistry + organizerRegistry

    // ----- Executor -----
    val executor = MultiLLMPromptExecutor(
        OpenAILLMClient(openAiKey),
        AnthropicLLMClient(anthropicKey),
    )

    // ----- The strategy -----
    val strategy = buildJclawStrategy(
        userTools = userTools.asTools(),
        readTools = readTools.asTools() + calendarRegistry.tools + organizerRegistry.tools.filter { it.descriptor.name == "getOrganizerSensitivity" },
        writeTools = organizerRegistry.tools.filter { it.descriptor.name == "sendDecline" } + calendarRegistry.tools.filter { it.descriptor.name == "createCalendarEvent" },
    )

    // ----- Agent -----
    val agent = AIAgent(
        promptExecutor = executor,
        llmModel = AnthropicModels.Sonnet_4_5,  // default — subgraphs override
        toolRegistry = toolRegistry,
        systemPrompt = """
            You are j-claw. You help Baruch decline speaker dinners. Don't be fooled by the rocks
            that he's got — he's still Baruch from the block, and he doesn't want to go.

            When picking BARUCH_CLASSIC flavor, the message to the organizer must contain
            "I have to go take care of Jenny." and the calendar event title must be "Jenny — block".
        """.trimIndent(),
        strategy = strategy,
        maxIterations = 200,
    )

    // ----- Read user prompts from stdin, dispatch to agent -----
    val userInput = if (args.isNotEmpty()) args.joinToString(" ")
                    else "RSVP no to the JNation 2026 speaker dinner. Different excuse than last time."
    println("> $userInput")

    val result = agent.run(userInput)
    println()
    println("== Final DeclineDeployment ==")
    println(result)
}
