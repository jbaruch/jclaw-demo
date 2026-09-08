package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import jclaw.Observability.langfuse
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
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * ROUND 2 in the three-pane TUI. Same agent and the same two MCP servers as
 * Main.kt. Tool calls, and the servers' own log lines, land in TRACE, where they
 * stay legible at streaming resolution instead of racing past in a log.
 */
fun main(args: Array<String>) {
    JclawTui.quietStdStreams(Path("jclaw-tui.log"))
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }

    val submissions = Channel<String>(Channel.UNLIMITED)
    val tui = JclawTui(onSubmit = { submissions.trySend(it) }, features = listOf("MCP"))
    var procs: List<Process> = emptyList()

    // The agent is created inside its scope; closing it must happen from the TUI's shutdown path.
    var closeAgent: (suspend () -> Unit)? = null

    val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jclaw-agent"))
    agentScope.launch {
        val (tools, servers) = Mcp.registry("calendar-mcp", "organizer-mcp", onStderr = { tui.trace(it, TraceKind.TOOL_CALL) })
        procs = servers
        tui.trace("tools discovered: " + tools.tools.joinToString { it.name }, TraceKind.SUBGRAPH_START)

        val jclaw = AIAgent(
            id = "j-claw",   // names the agent spans in Langfuse; a UUID otherwise
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            systemPrompt = PERSONA,
            llmModel = Models.flash,
            toolRegistry = tools,
        ) {
            if (Observability.enabled) install(OpenTelemetry) {
                langfuse(2, "tools", metadata = mapOf("model" to Models.flash.id))
            }
            handleEvents {
                onToolCallStarting { tui.trace("   ↪ ${it.toolName}(${it.toolArgs})", TraceKind.TOOL_CALL) }
                onLLMCallStarting {
                    tui.trace("→ ${it.model.id} · ${it.prompt.messages.size} messages · ${it.tools.size} tools", TraceKind.LLM)
                    tui.startBusy()
                }
                onLLMCallCompleted { _ -> tui.stopBusy() }
            }
        }
        closeAgent = { jclaw.close(); Observability.flush() }

        tui.chat("j-claw. Ask it for something.", ChatKind.OK)

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
    } catch (t: Throwable) {
        JclawTui.restoreStdStreams()
        println("j-claw TUI died: $t - details in jclaw-tui.log")
        t.printStackTrace()
        exitProcess(1)
    } finally {
        agentScope.cancel()
        // Closing ends the spans Koog still holds; the flush ships them (see Observability).
        closeAgent?.let { runBlocking { it() } }
        procs.forEach { it.destroyForcibly() }
        procs.forEach { runCatching { it.waitFor(2, TimeUnit.SECONDS) } }
        // MCP's stdio transport leaves a non-daemon reader thread alive.
        exitProcess(0)
    }
}
