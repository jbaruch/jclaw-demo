package jclaw.round1

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import jclaw.domain.Scenario
import kotlinx.coroutines.runBlocking

/**
 * ROUND 1 - "a chatbot in five minutes."
 *
 * This is the whole thing. One factory call. It answers, it is pleasant,
 * and it cannot do a single useful thing on Baruch's behalf - which is
 * the entire point of rounds 2 through 4.
 */
fun main() = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) {
        "GOOGLE_API_KEY is not set"
    }

    val jclaw = AIAgent(
        promptExecutor = simpleGoogleAIExecutor(apiKey),
        systemPrompt = Scenario.SYSTEM_PROMPT,
        llmModel = GoogleModels.Gemini3_5Flash,
    )

    val ask = "I got invited to the ${Scenario.EVENT_TITLE}. I don't want to go. Help."
    println("> $ask\n")
    println(jclaw.run(ask))
}
