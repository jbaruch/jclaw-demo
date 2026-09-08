package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import com.jbaruch.jclaw.tui.ChatKind
import com.jbaruch.jclaw.tui.JclawTui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * ROUND 1 in the three-pane TUI. The agent is the same one factory call as in
 * Main.kt; the TUI owns the main thread and the agent runs on its own scope.
 *
 * The opening sentence arrives as a program argument (./jclaw passes it), so the
 * round starts the moment the screen is up. Follow-ups are typed into PROMPT.
 */
fun main(args: Array<String>) {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }

    val submissions = Channel<String>(Channel.UNLIMITED)
    val tui = JclawTui(onSubmit = { submissions.trySend(it) })

    val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jclaw-agent"))
    agentScope.launch {
        val jclaw = AIAgent(
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            systemPrompt = PERSONA,
            llmModel = Models.flash,
        ) {
            handleEvents {
                onLLMCallStarting { _ -> tui.startBusy() }
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
    } finally {
        agentScope.cancel()
        // The HTTP client keeps non-daemon threads alive; a closed TUI must exit.
        exitProcess(0)
    }
}
