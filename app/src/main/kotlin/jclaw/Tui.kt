package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import jclaw.Observability.langfuse
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import com.jbaruch.jclaw.tui.ChatKind
import com.jbaruch.jclaw.tui.JclawTui
import com.jbaruch.jclaw.tui.StageState
import com.jbaruch.jclaw.tui.TraceKind
import com.jbaruch.jclaw.tui.TraceStageState
import jclaw.domain.Scenario
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
import kotlin.system.exitProcess

/**
 * Round 4 with the three-pane terminal UI instead of scrolling stdout.
 *
 * Same pipeline and JCLAW_NAIVE switch. The difference is
 * that the subtask boundaries and tool calls land in a TRACE pane where they
 * stay legible at streaming resolution, instead of racing past in a log.
 *
 * The TUI owns the main thread; the agent runs on its own scope. Every
 * agent -> UI call marshals through the render thread inside JclawTui, per
 * the tamboui render-thread-discipline rule.
 */
fun main(args: Array<String>) {
    JclawTui.quietStdStreams(Path("jclaw-tui.log"))
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val naive = System.getenv("JCLAW_NAIVE") == "1"

    val submissions = Channel<String>(Channel.UNLIMITED)
    val tui = JclawTui(
        onSubmit = { submissions.trySend(it) },
        features = listOf("MCP", "MEMORY", "WORKFLOW"),
        flow = listOf("identify", "→", "deploy", "→", "verify", "⇄", "refine"),
    )

    // The agent is created inside its scope; closing it must happen from the TUI's shutdown path.
    var closeAgent: (suspend () -> Unit)? = null

    val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jclaw-agent"))
    agentScope.launch {
        val mcp = Mcp.boot("calendar-mcp", "organizer-mcp", onStderr = { tui.trace(it, TraceKind.TOOL_CALL) })
        val memory = Memory.open(
            LLMEmbedder(GoogleLLMClient(apiKey), GoogleModels.Embeddings.GeminiEmbedding001),
            trace = { tui.trace(it.trim(), TraceKind.TOOL_CALL) },
        )

        tui.trace("mode: " + if (naive) "NAIVE — less context, no memory" else "DOMAIN-MODELLED", TraceKind.SUBGRAPH_START)
        tui.trace("models: ${Models.flash.id} → Claude subscription → Codex subscription", TraceKind.SUBGRAPH_START)

        val agent = AIAgent(
            id = "j-claw",   // names the agent spans in Langfuse; a UUID otherwise
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            agentConfig = AIAgentConfig.withSystemPrompt(
                prompt = Scenario.SYSTEM_PROMPT,
                llm = Models.flash,
                maxAgentIterations = 200,
            ),
            strategy = jclawStrategy(mcp, naive,
                onStage = { stage, model, state ->
                    tui.traceStage(stage, model, when (state) {
                        PipelineStageState.STARTED -> TraceStageState.STARTED
                        PipelineStageState.COMPLETED -> TraceStageState.COMPLETED
                        PipelineStageState.FAILED -> TraceStageState.FAILED
                    })
                    tui.stage(stage, when (state) {
                        PipelineStageState.STARTED -> StageState.ACTIVE
                        PipelineStageState.COMPLETED -> StageState.DONE
                        PipelineStageState.FAILED -> StageState.FAILED
                    })
                    if (state == PipelineStageState.STARTED) tui.startBusy() else tui.stopBusy()
                },
                onVerdict = { tui.chat(it, ChatKind.JCLAW) },
            ),
            toolRegistry = mcp.registry,
        ) {

            // Real traces, when there is somewhere to send them: see Observability.
            if (Observability.enabled) install(OpenTelemetry) {
                langfuse(
                    round = 4,
                    if (naive) "naive" else "domain-modelled",
                    "critic:codex", "drafter:claude-code",
                    metadata = mapOf("model" to Models.flash.id, "drafter" to "claude-code", "critic" to "codex"),
                )
            }
            if (!naive) install(LongTermMemory) {
                retrieval {
                    storage = memory
                    searchStrategy = SimilaritySearchStrategy(topK = 5)
                }
            }
            handleEvents {
                onSubgraphExecutionStarting {
                    if (it.subgraph.name in setOf("classify", "identify", "chatReply")) {
                        tui.traceStage(it.subgraph.name, "${Models.flash.id} (API)", TraceStageState.STARTED)
                    } else {
                        tui.trace("┌─ ▶ ${it.subgraph.name}", TraceKind.SUBGRAPH_START)
                    }
                    tui.stage(it.subgraph.name, StageState.ACTIVE)
                }
                onSubgraphExecutionCompleted {
                    if (it.subgraph.name in setOf("classify", "identify", "chatReply")) {
                        tui.traceStage(it.subgraph.name, "${Models.flash.id} (API)", TraceStageState.COMPLETED)
                    } else {
                        tui.trace("└─ ✓ ${it.subgraph.name}", TraceKind.SUBGRAPH_END)
                    }
                    tui.stage(it.subgraph.name, StageState.DONE)
                }
                onToolCallStarting { tui.trace("   ↪ ${it.toolName}(${it.toolArgs})", TraceKind.TOOL_CALL) }
                onLLMCallStarting { _ -> tui.startBusy() }
                onLLMCallCompleted { _ -> tui.stopBusy() }
            }
        }
        closeAgent = { agent.close(); Observability.flush(); mcp.close() }

        tui.chat(
            "j-claw: At your service. There is a mandatory training on your calendar. " +
                "Say the word and it will stop being your problem.",
            ChatKind.OK,
        )

        // A program argument, if given, is asked on startup (the smoke tests use it); on
        // stage the sentence is pasted. JclawTui echoes what you type, so only the
        // argument needs echoing here.
        var next: String? = args.joinToString(" ").ifBlank { null }
        next?.let { tui.chat("you: $it", ChatKind.YOU) }

        while (true) {
            val prompt = next ?: submissions.receive()
            next = null
            var deliveryAttempted = false
            try {
                tui.resetFlow()
                val result = agent.run(prompt)
                tui.finishTraceStages(TraceStageState.COMPLETED)
                if (result is JclawResult.ChatReply) {
                    tui.chat("j-claw: ${result.text}", ChatKind.JCLAW)
                    continue
                }
                if (result is JclawResult.Blocked) {
                    tui.stage("verify", StageState.FAILED)
                    tui.chat("BLOCKED: ${result.reason}", ChatKind.ERR)
                    tui.chat("Nothing was sent. There is no send override.", ChatKind.ERR)
                    continue
                }
                val ready = result as JclawResult.ReadyToSend
                val plan = ready.deployment
                tui.chat("j-claw: ✓ Codex approved — flavor ${plan.flavor}", ChatKind.OK)
                tui.chat("j-claw: ${plan.messageToOrganizer}", ChatKind.JCLAW)
                tui.chat("j-claw: hallway script → ${plan.hallwayScript}", ChatKind.JCLAW)
                val delivered = deliverApproved(ready,
                    confirm = {
                        tui.chat("Send it? type 'send' to deliver, anything else to hold.", ChatKind.OK)
                        submissions.receive().trim().equals("send", ignoreCase = true)
                    },
                    send = {
                        deliveryAttempted = true
                        val receipt = mcp.call("organizer-mcp", "sendDecline",
                            mapOf("eventId" to Scenario.EVENT_ID, "message" to it.messageToOrganizer))
                        tui.chat("j-claw: delivered. $receipt", ChatKind.OK)
                        try {
                            memory.add(listOf(Memory.story(Scenario.EVENT_TITLE, Scenario.ORGANIZER, it.flavor.name, it.messageToOrganizer)))
                        } catch (error: Exception) {
                            tui.chat("Delivered, but could not save to memory: ${error.message}", ChatKind.ERR)
                        }
                    },
                )
                if (!delivered) tui.chat("j-claw: held. Nothing was sent.", ChatKind.OK)
            } catch (c: CancellationException) {
                tui.finishTraceStages(TraceStageState.CANCELLED)
                throw c
            } catch (t: Throwable) {
                tui.finishTraceStages(TraceStageState.FAILED)
                tui.chat(
                    if (deliveryAttempted) "Delivery attempt failed: ${t.message}. Check the organizer receipt before retrying."
                    else "BLOCKED: ${t.message ?: t.javaClass.simpleName}. Nothing was sent.",
                    ChatKind.ERR,
                )
                t.printStackTrace()
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
        // Same reason as the CLI front end: the MCP reader thread will not let the
        // JVM exit once the TUI has been closed.
        exitProcess(0)
    }
}
