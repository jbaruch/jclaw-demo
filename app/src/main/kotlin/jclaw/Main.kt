package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import jclaw.domain.Scenario
import kotlinx.coroutines.runBlocking

/**
 * ROUND 1 - "a chatbot in five minutes."
 *
 * The agent is one factory call. Everything below it is a read-print loop, which
 * is the honest shape of a chatbot: you talk, it answers, forever.
 *
 * Two things worth noticing while you talk to it, because rounds 2 and 3 are
 * about exactly these:
 *
 *   - it cannot DO anything. It will happily draft you an excuse and has no way
 *     to put it in a calendar or send it to anyone.
 *   - it does not remember. Every turn starts from nothing, so ask it what you
 *     just said and watch it not know.
 */
fun main(): Unit = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) {
        "GOOGLE_API_KEY is not set"
    }

    val jclaw = AIAgent(
        promptExecutor = simpleGoogleAIExecutor(apiKey),
        systemPrompt = Scenario.SYSTEM_PROMPT,
        llmModel = Models.flash,
    )

    println("j-claw. Say something. (blank line or ctrl-D to quit)\n")

    while (true) {
        print("you: ")
        val line = readlnOrNull()?.trim()
        if (line.isNullOrEmpty()) break
        println()
        println("j-claw: " + jclaw.run(line))
        println()
    }
    println("bye.")
}
