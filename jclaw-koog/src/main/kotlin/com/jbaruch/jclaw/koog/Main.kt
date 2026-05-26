package com.jbaruch.jclaw.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.jbaruch.jclaw.tui.ChatKind
import com.jbaruch.jclaw.tui.JclawTui
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * j-claw — Round 1 (chatbot) entry point.
 *
 * Layout:
 *   - No MCP servers, no tools, no memory, no strategy
 *   - Just an `AIAgent(...)` with a prompt executor, a model, and a system prompt
 *   - The TUI types prompts in, the agent types replies back — nothing more
 *
 * The point of Round 1: this is the baseline. It's a chatbot. The next three
 * rounds add tools + MCPs (Round 2), memory (Round 3), and the typed pipeline
 * (Round 4) — and only at Round 4 does this become a real agent.
 *
 * Required env: OPENAI_API_KEY, ANTHROPIC_API_KEY.
 */
fun main(args: Array<String>) {
    val openAiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Set OPENAI_API_KEY")
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
        ?: error("Set ANTHROPIC_API_KEY")

    val submissions = Channel<String>(Channel.UNLIMITED)
    val tui = JclawTui(onSubmit = { submissions.trySend(it) })

    Thread {
        runBlocking {
            val executor = MultiLLMPromptExecutor(
                OpenAILLMClient(openAiKey),
                AnthropicLLMClient(anthropicKey),
            )

            val agent = AIAgent(
                promptExecutor = executor,
                llmModel = AnthropicModels.Sonnet_4_5,
                toolRegistry = ToolRegistry { },
                systemPrompt = """
                    You are j-claw. You help Baruch decline speaker dinners. Don't be fooled
                    by the rocks that he's got — he's still Baruch from the block, and he
                    doesn't want to go.

                    You are a chatbot with no tools, no calendar, no memory of past
                    conversations. Be charming and Wodehouse-esque, suggest excuses he could
                    use, but don't pretend to actually send anything or look anything up.
                """.trimIndent(),
                maxIterations = 50,
            ) {
                handleEvents {
                    onLLMCallStarting { _ -> tui.startBusy() }
                    onLLMCallCompleted { _ -> tui.stopBusy() }
                }
            }

            // Greet, then loop. Each PROMPT submission is a fresh agent.run() — no history
            // across turns, the chatbot is stateless.
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

    tui.run()
}
