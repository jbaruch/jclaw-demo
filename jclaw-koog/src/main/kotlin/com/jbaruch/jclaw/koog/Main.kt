package com.jbaruch.jclaw.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.fromProcess
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.jbaruch.jclaw.tui.ChatKind
import com.jbaruch.jclaw.tui.JclawTui
import com.jbaruch.jclaw.tui.TraceKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * j-claw — Round 4 (full pipeline) entry point with TamboUI three-pane TUI.
 *
 * Layout:
 *   - Two mock MCP servers (conference-mcp, contacts-mcp) launched as subprocesses
 *   - Three sliced ToolSets (UserTools = local, ReadTools = local + MCP, WriteTools = MCP)
 *   - In-process pre-seeded "memory" via SeedMemory.priorDeclines
 *   - Four-phase strategy from Strategy.kt
 *   - TamboUI TUI: CHAT / TRACE / STATUS / PROMPT
 *
 * The TUI runs on the main thread (blocking). A daemon thread drives the agent —
 * peeling the first PROMPT submission as the agent's input, leaving subsequent
 * submissions for UserTools' reactions channel.
 *
 * Required env: OPENAI_API_KEY, ANTHROPIC_API_KEY.
 */
fun main(args: Array<String>) {
    val openAiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Set OPENAI_API_KEY")
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
        ?: error("Set ANTHROPIC_API_KEY")

    // ----- One channel for ALL TUI submissions; first is initial input, rest are reactions -----
    val submissions = Channel<String>(Channel.UNLIMITED)

    val tui = JclawTui(onSubmit = { submissions.trySend(it) })

    // ----- Drive the agent on a daemon thread; TUI runs on main thread (blocks) -----
    Thread {
        runBlocking {
            // MCP servers (subprocesses; tools come back over stdio)
            val conferenceRegistry = McpToolRegistryProvider.fromProcess(
                ProcessBuilder("./mocks/conference-mcp/build/install/conference-mcp/bin/conference-mcp").start()
            )
            val contactsRegistry = McpToolRegistryProvider.fromProcess(
                ProcessBuilder("./mocks/contacts-mcp/build/install/contacts-mcp/bin/contacts-mcp").start()
            )

            // Local tools: askBaruch / awaitReaction pipe to the TUI's chat pane,
            // reactions are pulled from the shared submissions channel.
            val userTools = UserTools(
                outbound = { line -> tui.chat(line, ChatKind.JCLAW) },
                reactions = submissions,
            )
            val readTools = ReadTools(priorDeclines = SeedMemory.priorDeclines)

            val localRegistry = ToolRegistry {
                tools(userTools.asTools())
                tools(readTools.asTools())
            }
            val toolRegistry = localRegistry + conferenceRegistry + contactsRegistry

            val executor = MultiLLMPromptExecutor(
                OpenAILLMClient(openAiKey),
                AnthropicLLMClient(anthropicKey),
            )

            // Tool slices: conference-mcp is read-only; contacts splits read / write.
            val confRead     = conferenceRegistry.tools
            val contactsRead = contactsRegistry.tools.filter { it.descriptor.name == "getContactSensitivity" }
            val contactsWrt  = contactsRegistry.tools.filter { it.descriptor.name == "sendDecline" }

            val strategy = buildJclawStrategy(
                userTools  = userTools.asTools(),
                readTools  = readTools.asTools() + confRead + contactsRead,
                writeTools = contactsWrt,
            )

            val agent = AIAgent(
                promptExecutor = executor,
                llmModel = AnthropicModels.Sonnet_4_5,
                toolRegistry = toolRegistry,
                systemPrompt = """
                    You are j-claw. You help Baruch decline speaker dinners. Don't be fooled by
                    the rocks that he's got — he's still Baruch from the block, and he doesn't
                    want to go.

                    When picking BARUCH_CLASSIC flavor, the messageToOrganizer must contain the
                    exact sentence "I have to go take care of Jenny." — that's the whole point
                    of the flavor.
                """.trimIndent(),
                strategy = strategy,
                maxIterations = 200,
            ) {
                handleEvents {
                    onSubgraphExecutionStarting { ctx ->
                        tui.trace("┌─ ▶ ${ctx.subgraph.name}", TraceKind.SUBGRAPH_START)
                    }
                    onSubgraphExecutionCompleted { ctx ->
                        val output = ctx.output?.toString()?.let {
                            if (it.length > 200) it.take(200) + "…" else it
                        }
                        tui.trace("└─ ✓ ${ctx.subgraph.name}  →  $output", TraceKind.SUBGRAPH_END)
                    }
                    onToolCallStarting { ctx ->
                        tui.trace("   ↪ ${ctx.toolName}(${ctx.toolArgs})", TraceKind.TOOL_CALL)
                    }
                    onToolCallCompleted { ctx ->
                        formatToolForChat(ctx.toolName, ctx.toolArgs.toString(), ctx.toolResult.toString())?.let {
                            tui.chat(it, ChatKind.TOOL_RESULT)
                        }
                    }
                    onLLMCallStarting { _ -> tui.startBusy() }
                    onLLMCallCompleted { _ -> tui.stopBusy() }
                }
            }

            // Greet, then wait for the initial user message.
            tui.chat(
                "j-claw: Good evening, sir. The hour grows civil and your calendar grows uncivil. " +
                    "From which obligation are we to engineer your gracious extraction tonight — " +
                    "with grace, with conviction, and with the absolute minimum of perjury?",
                ChatKind.JCLAW,
            )
            val initial = if (args.isNotEmpty()) args.joinToString(" ") else submissions.receive()
            if (args.isNotEmpty()) {
                // Auto-fire path also surfaces the implicit input in CHAT.
                tui.chat("you: $initial", ChatKind.YOU)
            }

            try {
                val result = agent.run(initial)
                tui.chat("j-claw: ✓ done — flavor ${result.flavor}", ChatKind.OK)
                tui.chat("j-claw: message → ${result.messageToOrganizer}", ChatKind.JCLAW)
            } catch (t: Throwable) {
                tui.chat("✘ ${t.message ?: t.javaClass.simpleName}", ChatKind.ERR)
            }
        }
    }.apply { isDaemon = true; name = "jclaw-agent" }.start()

    // Blocks the main thread until the user quits the TUI (Ctrl+C / q).
    tui.run()
}

/**
 * Formats a completed tool call as a single-line CHAT message so the audience sees what
 * j-claw learned. Returns null for tools that already produce their own CHAT output
 * (askBaruch / pingBaruchPrivate / awaitReaction — those go through UserTools.outbound).
 */
private fun formatToolForChat(toolName: String, args: String, result: String): String? {
    val resultShort = if (result.length > 220) result.take(220) + "…" else result
    return when (toolName) {
        // Already chat-visible via UserTools.outbound — skip.
        "askBaruch", "pingBaruchPrivate", "awaitReaction" -> null

        "searchPriorExcuses"     -> "🔍 j-claw checked prior declines → $resultShort"
        "getCalendar"            -> "📅 j-claw read the calendar → $resultShort"
        "getEventAttendees"      -> "👥 j-claw checked who's at the dinner → $resultShort"
        "getContactSensitivity"  -> "📇 j-claw looked up the organizer's sensitivity → $resultShort"
        "sendDecline"            -> "✉️  j-claw sent the decline → $resultShort"

        else -> "🔧 j-claw called $toolName → $resultShort"
    }
}
