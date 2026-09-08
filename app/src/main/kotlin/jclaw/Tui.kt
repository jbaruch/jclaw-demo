package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
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
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * ROUND 1 in the three-pane TUI. The agent is the same one factory call as in
 * Main.kt; the TUI owns the main thread and the agent runs on its own scope.
 *
 * The opening sentence is on the clipboard (./jclaw puts it there): paste it into
 * PROMPT. A program argument, if one is given, is asked on startup instead - that is
 * how the smoke tests drive it.
 *
 * TRACE shows the only thing there is to show: one model call per turn, with
 * zero tools. That emptiness is the point of round 1.
 */
fun main(args: Array<String>) {
    JclawTui.quietStdStreams(Path("jclaw-tui.log"))
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }

    val submissions = Channel<String>(Channel.UNLIMITED)
    val tui = JclawTui(onSubmit = { submissions.trySend(it) }, title = "ROUND 1 · CHATBOT")

    val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jclaw-agent"))
    agentScope.launch {
        val jclaw = AIAgent(
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            systemPrompt = PERSONA,
            llmModel = Models.flash,
        ) {
            handleEvents {
                onLLMCallStarting {
                    tui.trace("→ ${it.model.id} · ${it.prompt.messages.size} messages · ${it.tools.size} tools", TraceKind.LLM)
                    tui.startBusy()
                }
                onLLMCallCompleted { _ -> tui.stopBusy() }
            }
        }

        tui.chat("j-claw. Say something.", ChatKind.OK)

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
        // The HTTP client keeps non-daemon threads alive; a closed TUI must exit.
        exitProcess(0)
    }
}
