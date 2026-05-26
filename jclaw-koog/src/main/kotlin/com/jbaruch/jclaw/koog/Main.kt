package com.jbaruch.jclaw.koog

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.features.opentelemetry.integration.langfuse.addLangfuseExporter
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
 *   - Two MCP-backed tool surfaces (conference-mcp = read, contacts-mcp = read + write)
 *     plus a local UserTools for asking Baruch and awaiting his y/n reactions
 *   - Real Koog ChatMemory pre-seeded with prior-decline conversation turns
 *     (via SeedMemoryProvider — no searchPriorExcuses tool needed)
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

            val localRegistry = ToolRegistry {
                tools(userTools.asTools())
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
                readTools  = confRead + contactsRead,
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
                install(ChatMemory) {
                    chatHistoryProvider = SeedMemoryProvider(
                        onLoadTrace = { tui.trace(it, TraceKind.TOOL_CALL) },
                        onLoadChat  = { tui.chat(it, ChatKind.TOOL_RESULT) },
                    )
                }
                // Pipeline visualization — Koog ships an OpenTelemetry feature with a
                // built-in Langfuse exporter. Spans for every subgraph (classify,
                // identifyDecline, deployDecline, verifyDecline, refineDecline, chatReply),
                // every tool call, and every LLM call show up as a nested trace tree at
                // langfuse.com — the equivalent of LangChain4j-Agentic's System Report.
                // Reads LANGFUSE_HOST / LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY env vars.
                if (System.getenv("LANGFUSE_PUBLIC_KEY") != null) {
                    install(OpenTelemetry) {
                        setVerbose(true)  // emit prompts, completions, token counts on each span
                        addLangfuseExporter()
                    }
                }
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
                "j-claw: At your service, sir. Shall we engineer a gracious extraction " +
                    "from some obligation — or is there other business?",
                ChatKind.OK,
            )
            // Loop: every PROMPT submission is a fresh agent.run(). The classify subgraph
            // routes each prompt to either the decline pipeline or a chat reply, so the
            // agent stays alive for follow-ups instead of dying after a single decline.
            var next: String? = if (args.isNotEmpty()) args.joinToString(" ") else null
            if (next != null) {
                tui.chat("you: $next", ChatKind.YOU)
            }

            while (true) {
                val prompt = next ?: submissions.receive()
                next = null
                try {
                    when (val result = agent.run(prompt)) {
                        is JclawResult.DeclineSent -> {
                            val d = result.deployment
                            tui.chat("j-claw: ✓ done — flavor ${d.flavor}", ChatKind.OK)
                            tui.chat("j-claw: message → ${d.messageToOrganizer}", ChatKind.JCLAW)
                        }
                        is JclawResult.ChatReply -> {
                            tui.chat("j-claw: ${result.text}", ChatKind.JCLAW)
                        }
                    }
                } catch (t: Throwable) {
                    tui.chat("✘ ${t.message ?: t.javaClass.simpleName}", ChatKind.ERR)
                }
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

        "getCalendar"            -> "📅 j-claw read the calendar → $resultShort"
        "getEventAttendees"      -> "👥 j-claw checked who's at the dinner → $resultShort"
        "getContactSensitivity"  -> "📇 j-claw looked up the organizer's sensitivity → $resultShort"
        "sendDecline"            -> "✉️  j-claw sent the decline → $resultShort"

        else -> "🔧 j-claw called $toolName → $resultShort"
    }
}
