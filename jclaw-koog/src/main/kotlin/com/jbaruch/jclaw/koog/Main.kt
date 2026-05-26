package com.jbaruch.jclaw.koog

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.chatMemory.feature.InMemoryChatHistoryProvider
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.features.opentelemetry.integration.langfuse.addLangfuseExporter
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.augmentation.PromptAugmenter
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.fromProcess
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.jbaruch.jclaw.tui.ChatKind
import com.jbaruch.jclaw.tui.JclawTui
import com.jbaruch.jclaw.tui.TraceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * j-claw — Round 4 (full pipeline) entry point with TamboUI three-pane TUI.
 *
 * Layout:
 *   - Two mock MCP servers (conference-mcp, contacts-mcp) launched as subprocesses
 *   - Two MCP-backed tool surfaces (conference-mcp = read, contacts-mcp = read + write)
 *     plus a local UserTools for asking Baruch and awaiting his y/n reactions
 *   - Koog 1.0 LongTermMemory pre-seeded with three prior-excuse fact records —
 *     retrieved per LLM call and augmented into the system prompt (fact channel,
 *     not impersonated chat turns)
 *   - Koog ChatMemory in default in-process mode for in-session conversation
 *     history (the right channel for actual turns, distinct from facts)
 *   - Four-phase strategy from Strategy.kt
 *   - TamboUI TUI: CHAT / TRACE / STATUS / PROMPT
 *
 * The TUI runs on the main thread (blocking — TamboUI's render thread is whoever
 * calls run()). The agent loop runs on a dedicated `CoroutineScope` whose
 * SupervisorJob is cancelled in main()'s finally block when the user quits the
 * TUI — structured concurrency replaces the earlier `Thread { runBlocking { ... } }`
 * pattern. Agent → UI calls still marshal through `runner.runOnRenderThread`
 * inside JclawTui per `jbaruch/tamboui: render-thread-discipline`.
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

    // ----- Drive the agent on a dedicated CoroutineScope; TUI runs on main (blocks) -----
    // Dispatchers.IO matches the agent's workload (HTTP, MCP subprocesses, channel waits).
    // SupervisorJob means a single failed agent.run() doesn't tear the whole scope down;
    // the try/finally below cancels the scope when tui.run() returns.
    val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jclaw-agent"))
    agentScope.launch {
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
        val contactsWrt  = contactsRegistry.tools.filter { it.descriptor.name == "sendExcuse" }

        val strategy = buildJclawStrategy(
            userTools  = userTools.asTools(),
            readTools  = confRead + contactsRead,
            writeTools = contactsWrt,
        )

        // Pre-seed LongTermMemory before agent construction — the install lambda is
        // non-suspend, but priorExcusesStorage()'s storage.add() is suspend.
        //
        // Per-search callbacks surface a trace/chat event for every retrieval. Koog's
        // LongTermMemory fires retrieval before every LLM call (standard RAG shape),
        // so the audience sees one event per subgraph LLM round-trip — that's the
        // point: the trace makes it visible that the fact channel is consulted on
        // every call, which is how RAG-style augmentation works.
        val priorExcuses = priorExcusesStorage(
            onSearchTrace = { count ->
                tui.trace("   ↪ LongTermMemory.search → $count prior excuses", TraceKind.TOOL_CALL)
            },
            onSearchChat  = { count ->
                tui.chat("🧠 j-claw consulted memory → $count prior excuses retrieved", ChatKind.TOOL_RESULT)
            },
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
            // ChatMemory handles in-session conversation history (the right channel
            // for actual turns). Prior-excuse facts live in LongTermMemory below —
            // separating fact retrieval from chat impersonation removes the date-prefix
            // workaround that flagged the old single-channel design as wrong.
            install(ChatMemory) {
                chatHistoryProvider = InMemoryChatHistoryProvider()
            }
            install(LongTermMemory) {
                retrieval {
                    storage = priorExcuses
                    // Custom augmenter — bypasses two bugs in the built-in `SystemPromptAugmenter`
                    // shipped in `agents-features-longterm-memory:1.0.0-beta` (and reproduced
                    // identically in `1.0.0-beta-preview7`; verified byte-identical augment()
                    // body). This is the only beta module on the classpath; everything else
                    // is 1.0.0 stable.
                    //   (a) it appends a standalone `MessagePart.Text("\n\n")` separator part,
                    //       which Anthropic rejects with "system: text content blocks must
                    //       contain non-whitespace text" (OpenAI tolerates it, which is why this
                    //       only breaks the Sonnet-backed deployExcuse subgraph);
                    //   (b) its default template ends with "Answer the user's question…", which
                    //       confuses per-subgraph prompts that each have their own task.
                    // This lambda appends ONE combined text part with the separator baked in,
                    // and frames the facts as historical reference.
                    promptAugmenter = PromptAugmenter { originalPrompt, relevantContext ->
                        if (relevantContext.isEmpty()) return@PromptAugmenter originalPrompt
                        val systemIndex = originalPrompt.messages.indexOfFirst { it is Message.System }
                        if (systemIndex < 0) return@PromptAugmenter originalPrompt
                        val ctxText = PromptAugmenter.formatContext(relevantContext)
                        val appended = "\n\n" +
                            "Relevant facts from j-claw's long-term memory of prior excuses:\n\n" +
                            ctxText + "\n\n" +
                            "Use these facts when reasoning about previously used flavors, " +
                            "organizers, or events. They are read-only history, not today's task."
                        originalPrompt.withMessages { messages ->
                            val original = messages[systemIndex] as Message.System
                            val updated = original.copy(parts = original.parts + MessagePart.Text(appended))
                            messages.toMutableList().also { it[systemIndex] = updated }
                        }
                    }
                }
            }
            // Pipeline visualization — Koog ships an OpenTelemetry feature with a
            // built-in Langfuse exporter. Spans for every subgraph (classify,
            // identifyExcuse, deployExcuse, verifyExcuse, refineExcuse, chatReply),
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
                    tui.trace("└─ ✓ ${ctx.subgraph.name}  →  ${ctx.output.describe()}", TraceKind.SUBGRAPH_END)
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
        // routes each prompt to either the excuse pipeline or a chat reply, so the
        // agent stays alive for follow-ups instead of dying after a single excuse.
        var next: String? = if (args.isNotEmpty()) args.joinToString(" ") else null
        next?.let { tui.chat("you: $it", ChatKind.YOU) }

        while (true) {
            val prompt = next ?: submissions.receive()
            next = null
            try {
                when (val result = agent.run(prompt)) {
                    is JclawResult.ExcuseSent -> {
                        val d = result.deployment
                        tui.chat("j-claw: ✓ done — flavor ${d.flavor}", ChatKind.OK)
                        tui.chat("j-claw: message → ${d.messageToOrganizer}", ChatKind.JCLAW)
                    }
                    is JclawResult.ChatReply -> {
                        tui.chat("j-claw: ${result.text}", ChatKind.JCLAW)
                    }
                }
            } catch (c: CancellationException) {
                // Scope was cancelled (TUI exited) — propagate, do NOT render as a chat error.
                throw c
            } catch (t: Throwable) {
                tui.chat("✘ ${t.message ?: t.javaClass.simpleName}", ChatKind.ERR)
                // Stack to stderr — TUI takes stdout, stderr is free for diagnostic capture
                // (e.g. `./run.sh 2> /tmp/jclaw.err`). The chat line only shows the short
                // message; the trace below has the full provider response and call site.
                t.printStackTrace()
            }
        }
    }

    // Blocks the main thread until the user quits the TUI (Ctrl+C / q).
    try {
        tui.run()
    } finally {
        agentScope.cancel("TUI exited")
    }
}

/**
 * Formats a completed tool call as a single-line CHAT message so the audience sees what
 * j-claw learned. Returns null for tools that already produce their own CHAT output
 * (askBaruch / pingBaruchPrivate / awaitReaction — those go through UserTools.outbound).
 */
private fun formatToolForChat(toolName: String, args: String, result: String): String? {
    return when (toolName) {
        // Already chat-visible via UserTools.outbound — skip.
        "askBaruch", "pingBaruchPrivate", "awaitReaction" -> null

        "getCalendar"            -> "📅 j-claw read the calendar → $result"
        "getEventAttendees"      -> "👥 j-claw checked who's at the dinner → $result"
        "getContactSensitivity"  -> "📇 j-claw looked up the organizer's sensitivity → $result"
        "sendExcuse"             -> "✉️  j-claw sent the excuse → $result"

        else -> "🔧 j-claw called $toolName → $result"
    }
}

private val DEFAULT_TO_STRING = Regex("""^[\w.$]+@[0-9a-fA-F]+$""")

/**
 * Render any object readably for the TRACE pane. If the receiver overrides toString
 * (data classes, enums, primitives, strings, collections) we use it directly. If toString
 * falls through to Object's default ("pkg.Class@hex") — which happens for Koog framework
 * types like CriticResult subtypes that don't ship a toString — we recurse over the
 * object's declared fields via reflection and emit ClassName(field=value, ...).
 */
private fun Any?.describe(): String {
    if (this == null) return "null"
    val s = toString()
    if (!DEFAULT_TO_STRING.matches(s)) return s
    val cls = this::class.java
    val fields = cls.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
    if (fields.isEmpty()) return cls.simpleName
    val parts = fields.joinToString(", ") { f ->
        f.isAccessible = true
        val fv = runCatching { f.get(this) }.getOrNull()
        "${f.name}=${fv.describe()}"
    }
    return "${cls.simpleName}($parts)"
}
