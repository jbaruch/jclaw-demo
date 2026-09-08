package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.longtermmemory.feature.FailurePolicy
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
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
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * ROUND 3 in the three-pane TUI. Same agent, same servers, same memory directory
 * as Main.kt. Memory retrieval and writes show up in TRACE next to the tool calls,
 * so the audience sees the read before the first tool and the write after the send.
 */
fun main(args: Array<String>) {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }

    val submissions = Channel<String>(Channel.UNLIMITED)
    val tui = JclawTui(onSubmit = { submissions.trySend(it) })
    var procs: List<Process> = emptyList()

    val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jclaw-agent"))
    agentScope.launch {
        val (tools, servers) = Mcp.registry("calendar-mcp", "organizer-mcp", onStderr = { tui.trace(it, TraceKind.TOOL_CALL) })
        procs = servers

        // Memory is a directory on disk. memory/documents/ is what j-claw told Dana before:
        // three committed files today, one more after every run. Nothing is seeded in code.
        val memory = Memory.open(
            LLMEmbedder(GoogleLLMClient(apiKey), GoogleModels.Embeddings.GeminiEmbedding001),
            trace = { tui.trace(it.trim(), TraceKind.SUBGRAPH_END) },
        )

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
                ingestion {
                    storage = memory
                    documentExtractor = Memory.whatJclawToldDana
                    failurePolicy = FailurePolicy.FAIL_FAST
                }
            }
            handleEvents {
                onToolCallStarting { tui.trace("   ↪ ${it.toolName}(${it.toolArgs})", TraceKind.TOOL_CALL) }
                onLLMCallStarting { _ -> tui.startBusy() }
                onLLMCallCompleted { _ -> tui.stopBusy() }
            }
        }

        tui.chat("j-claw, with a memory this time.", ChatKind.OK)

        // JclawTui echoes what you type; only the argument needs echoing here.
        var next: String? = args.joinToString(" ").ifBlank { null }
        next?.let { tui.chat("you: $it", ChatKind.YOU) }

        while (true) {
            val prompt = next ?: submissions.receive()
            next = null
            try {
                tui.chat("j-claw: " + jclaw.run(prompt), ChatKind.JCLAW)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                tui.chat("✘ ${t.message ?: t.javaClass.simpleName}", ChatKind.ERR)
            }
        }
    }

    try {
        tui.run()
    } finally {
        agentScope.cancel()
        procs.forEach { it.destroyForcibly() }
        procs.forEach { runCatching { it.waitFor(2, TimeUnit.SECONDS) } }
        // MCP's stdio transport leaves a non-daemon reader thread alive.
        exitProcess(0)
    }
}
