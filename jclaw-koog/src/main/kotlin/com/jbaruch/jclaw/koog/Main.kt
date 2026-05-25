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
    val conferenceRegistry = McpToolRegistryProvider.fromProcess(
        ProcessBuilder("./mocks/conference-mcp/build/install/conference-mcp/bin/conference-mcp").start()
    )
    val contactsRegistry = McpToolRegistryProvider.fromProcess(
        ProcessBuilder("./mocks/contacts-mcp/build/install/contacts-mcp/bin/contacts-mcp").start()
    )

    // ----- Local tools -----
    val chatOutbound: (String) -> Unit = { println(it) }
    val reactions = Channel<String>(capacity = 16)

    // stdin reader — daemon thread so it doesn't block JVM shutdown.
    Thread {
        val reader = System.`in`.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) reactions.trySend(trimmed)
        }
    }.apply { isDaemon = true; name = "stdin-reactions" }.start()

    val userTools = UserTools(outbound = chatOutbound, reactions = reactions)
    val readTools = ReadTools(priorDeclines = SeedMemory.priorDeclines)

    val localRegistry = ToolRegistry {
        tools(userTools.asTools())
        tools(readTools.asTools())
    }

    val toolRegistry = localRegistry + conferenceRegistry + contactsRegistry

    // ----- Executor -----
    val executor = MultiLLMPromptExecutor(
        OpenAILLMClient(openAiKey),
        AnthropicLLMClient(anthropicKey),
    )

    // ----- The strategy -----
    // conference-mcp is read-only (getCalendar + getEventAttendees).
    // contacts-mcp splits read (getContactSensitivity) vs write (sendDecline).
    val confRead     = conferenceRegistry.tools
    val contactsRead = contactsRegistry.tools.filter { it.descriptor.name == "getContactSensitivity" }
    val contactsWrt  = contactsRegistry.tools.filter { it.descriptor.name == "sendDecline" }

    val strategy = buildJclawStrategy(
        userTools  = userTools.asTools(),
        readTools  = readTools.asTools() + confRead + contactsRead,
        writeTools = contactsWrt,
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
