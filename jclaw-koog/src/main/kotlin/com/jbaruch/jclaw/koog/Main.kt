package com.jbaruch.jclaw.koog

import ai.koog.agents.chatMemory.feature.ChatMemory
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
 * j-claw — Round 3 (tools + MCP + memory, no pipeline yet) entry point.
 *
 * Layout:
 *   - Two mock MCP servers (conference-mcp, contacts-mcp) launched as subprocesses
 *   - Two MCP-backed tool surfaces (conference-mcp = read, contacts-mcp = read + write)
 *     plus a local UserTools for asking Baruch and awaiting his y/n reactions
 *   - Real Koog ChatMemory pre-seeded with prior-decline conversation turns
 *     (via SeedMemoryProvider — no searchPriorExcuses tool needed)
 *   - NO strategy — the agent reasons freely over the available tools. The
 *     four-phase typed pipeline arrives in Round 4.
 *   - TamboUI TUI: CHAT / TRACE / STATUS / PROMPT
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

            val agent = AIAgent(
                promptExecutor = executor,
                llmModel = AnthropicModels.Sonnet_4_5,
                toolRegistry = toolRegistry,
                systemPrompt = """
                    Today's date: 2026-05-26. You are j-claw. You help Baruch decline speaker
                    dinners. Don't be fooled by the rocks that he's got — he's still Baruch
                    from the block, and he doesn't want to go.

                    RULES FOR DECLINE REQUESTS — read before doing anything else:

                    R1. NEVER write a plan, commentary, or "let me check…" text. Skip straight
                        to the next tool call. Talking about a tool is not calling it.
                    R2. NEVER end your turn before sendDecline has returned. A response with
                        text-only and no tool call is a FAILED task unless sendDecline already
                        returned this turn.
                    R3. The sequence is fixed. Call these tools, in order, with NO chat output
                        between them:
                          1) getCalendar
                          2) getEventAttendees
                          3) getContactSensitivity
                          4) sendDecline   ← MANDATORY. Without this call the task FAILED.
                    R4. Pick a flavor that does NOT appear in your memory for the same
                        organizer or venue. The pre-loaded turns tagged with [YYYY-MM-DD] are
                        historical — check them. EMERGENCY_MEETING / FAMILY_OBLIGATION /
                        HOTEL_ISSUE are typically burned. Prefer JET_LAG_HONEST or DEADLINE.
                    R5. ONLY after sendDecline returns, write ONE short summary line telling
                        Baruch what you sent and to whom. Never before.

                    FOR FOLLOW-UPS / QUESTIONS / SMALL TALK: just answer in chat. Don't run
                    the workflow. Read your memory if asked about past declines. If you don't
                    know, say so plainly.
                """.trimIndent(),
                maxIterations = 200,
            ) {
                install(ChatMemory) {
                    chatHistoryProvider = SeedMemoryProvider(
                        onLoadTrace = { tui.trace(it, TraceKind.TOOL_CALL) },
                        onLoadChat  = { tui.chat(it, ChatKind.TOOL_RESULT) },
                    )
                }
                handleEvents {
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

            // Greet, then loop: every PROMPT submission is a fresh agent.run(). No history
            // is carried between turns — ChatMemory loads the pre-seeded prior declines on
            // each call, but the current run's turns aren't persisted.
            tui.chat(
                "j-claw: At your service, sir. Shall we engineer a gracious extraction " +
                    "from some obligation — or is there other business?",
                ChatKind.OK,
            )

            var next: String? = if (args.isNotEmpty()) args.joinToString(" ") else null
            if (next != null) {
                tui.chat("you: $next", ChatKind.YOU)
            }

            while (true) {
                val prompt = next ?: submissions.receive()
                next = null
                try {
                    val result = agent.run(prompt)
                    tui.chat("j-claw: $result", ChatKind.JCLAW)
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
