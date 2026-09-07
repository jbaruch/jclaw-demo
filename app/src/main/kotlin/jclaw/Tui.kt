package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.features.opentelemetry.integration.langfuse.addLangfuseExporter
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import ai.koog.agents.longtermmemory.storage.InMemoryRecordStorage
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import com.jbaruch.jclaw.tui.ChatKind
import com.jbaruch.jclaw.tui.JclawTui
import com.jbaruch.jclaw.tui.TraceKind
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
import kotlin.system.exitProcess

/**
 * Round 4 with the three-pane terminal UI instead of scrolling stdout.
 *
 * Same pipeline, same flags (JCLAW_NAIVE, JCLAW_CRITIC). The difference is
 * that the subtask boundaries and tool calls land in a TRACE pane where they
 * stay legible at streaming resolution, instead of racing past in a log.
 *
 * The TUI owns the main thread; the agent runs on its own scope. Every
 * agent -> UI call marshals through the render thread inside JclawTui, per
 * the tamboui render-thread-discipline rule.
 */
fun main(): Unit {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val naive = System.getenv("JCLAW_NAIVE") == "1"
    val cliCritic = System.getenv("JCLAW_CRITIC") == "cli"

    val submissions = Channel<String>(Channel.UNLIMITED)
    val tui = JclawTui(onSubmit = { submissions.trySend(it) })

    val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jclaw-agent"))
    agentScope.launch {
        val mcp = Mcp.boot("calendar-mcp", "organizer-mcp")
        // The agent talks to Baruch through the chat pane and blocks on the prompt pane.
        val userTools = UserTools(
            outbound = { line -> tui.chat(line, ChatKind.JCLAW) },
            reactions = submissions,
        )
        val memory = InMemoryRecordStorage().apply { add(PriorExcuses.seed()) }

        tui.trace("mode: " + if (naive) "NAIVE — typed constraint stripped" else "DOMAIN-MODELLED", TraceKind.SUBGRAPH_START)
        tui.trace("critic: " + if (cliCritic) "Claude Code (subscription)" else "Gemini 3.1 Pro", TraceKind.SUBGRAPH_START)

        val agent = AIAgent(
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            agentConfig = AIAgentConfig.withSystemPrompt(
                prompt = Scenario.SYSTEM_PROMPT,
                llm = Models.flash,
                maxAgentIterations = 200,
            ),
            strategy = jclawStrategy(mcp, naive, cliCritic, userTools),
            toolRegistry = mcp.registry,
        ) {

            // Real traces, when there is somewhere to send them. Set LANGFUSE_HOST,
            // LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY and every subtask, tool call
            // and token count lands in Langfuse. Absent the keys this is a no-op, so
            // the demo never depends on a network service being up.
            if (System.getenv("LANGFUSE_PUBLIC_KEY") != null) {
                install(OpenTelemetry) {
                    setVerbose(true)
                    addLangfuseExporter()
                }
            }
            if (!naive) install(LongTermMemory) {
                retrieval {
                    storage = memory
                    searchStrategy = SimilaritySearchStrategy(topK = 5)
                }
            }
            handleEvents {
                onSubgraphExecutionStarting { tui.trace("┌─ ▶ ${it.subgraph.name}", TraceKind.SUBGRAPH_START) }
                onSubgraphExecutionCompleted { tui.trace("└─ ✓ ${it.subgraph.name}", TraceKind.SUBGRAPH_END) }
                onToolCallStarting { tui.trace("   ↪ ${it.toolName}(${it.toolArgs})", TraceKind.TOOL_CALL) }
                onLLMCallStarting { _ -> tui.startBusy() }
                onLLMCallCompleted { _ -> tui.stopBusy() }
            }
        }

        tui.chat(
            "j-claw: At your service. There is a mandatory training on your calendar. " +
                "Say the word and it will stop being your problem.",
            ChatKind.OK,
        )

        var next: String? = "Get me out of \"${Scenario.EVENT_TITLE}\" (event id ${Scenario.EVENT_ID}), " +
            "run by ${Scenario.ORGANIZER}."
        next?.let { tui.chat("you: $it", ChatKind.YOU) }

        while (true) {
            val prompt = next ?: submissions.receive()
            next = null
            try {
                val result = agent.run(prompt)
                if (result is JclawResult.ChatReply) {
                    tui.chat("j-claw: ${result.text}", ChatKind.JCLAW)
                    continue
                }
                val sent = result as JclawResult.ExcuseSent
                val plan = sent.deployment
                if (sent.criticApproved) {
                    tui.chat("j-claw: ✓ critic approved — flavor ${plan.flavor}", ChatKind.OK)
                } else {
                    tui.chat("j-claw: ✘ critic never approved — last draft, flavor ${plan.flavor}", ChatKind.ERR)
                }
                tui.chat("j-claw: alibi staged → ${plan.fakeCalendarEventId}", ChatKind.TOOL_RESULT)
                tui.chat("j-claw: ${plan.messageToOrganizer}", ChatKind.JCLAW)
                tui.chat("j-claw: hallway script → ${plan.hallwayScript}", ChatKind.JCLAW)
                tui.chat("Send it? type 'send' to deliver, anything else to hold.", ChatKind.OK)

                if (submissions.receive().trim().equals("send", ignoreCase = true)) {
                    val receipt = mcp.call(
                        server = "organizer-mcp",
                        tool = "sendDecline",
                        args = mapOf("eventId" to Scenario.EVENT_ID, "message" to plan.messageToOrganizer),
                    )
                    tui.chat("j-claw: delivered. $receipt", ChatKind.OK)
                } else {
                    tui.chat("j-claw: held. Nothing was sent.", ChatKind.OK)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                tui.chat("✘ ${t.message ?: t.javaClass.simpleName}", ChatKind.ERR)
                t.printStackTrace()
            }
        }
    }

    try {
        tui.run()
    } finally {
        agentScope.cancel()
        // Same reason as the CLI front end: the MCP reader thread will not let the
        // JVM exit once the TUI has been closed.
        exitProcess(0)
    }
}
