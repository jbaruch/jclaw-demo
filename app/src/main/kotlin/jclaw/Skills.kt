package jclaw

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import kotlinx.coroutines.runBlocking

/** Optional stdout runner. Normal chat uses the same runtime catalog and file tools. */
fun main(args: Array<String>): Unit = runBlocking {
    val request = skillRewriteRequest(args, System.getenv()) { System.`in`.bufferedReader().readText() }
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val skills = AgentSkills.discover()
    println("\n--- source message ---\n${request.message}")
    val agent = AIAgent(
        id = "j-claw-skills",
        promptExecutor = simpleGoogleAIExecutor(apiKey),
        systemPrompt = "${Persona.PROMPT}\n${skills.prompt}",
        llmModel = Models.flash,
        toolRegistry = skills.registry,
    ) {
        handleEvents { onToolCallStarting { println("      tool ${it.toolName}(${it.toolArgs})") } }
    }
    try {
        println("\n--- rewritten message (intensity ${request.level}) ---")
        println(agent.run("Use the corporate-speak skill at intensity ${request.level} to rewrite this message:\n${request.message}"))
    } finally {
        agent.close()
    }
}

internal data class SkillRewriteRequest(val level: Int, val message: String)

/** Explicit level wins over the environment. Source text is required: args, env, then stdin. */
internal fun skillRewriteRequest(
    args: Array<String>, environment: Map<String, String>, readInput: () -> String,
): SkillRewriteRequest {
    val explicitLevel = args.firstOrNull()?.toIntOrNull()
    val configuredLevel = explicitLevel ?: environment["JCLAW_LEVEL"]?.let {
        requireNotNull(it.toIntOrNull()) { "JCLAW_LEVEL must be an integer from 1 to 11" }
    } ?: 11
    require(configuredLevel in 1..11) { "Skill intensity must be from 1 to 11" }
    val textArgs = if (explicitLevel == null) args.toList() else args.drop(1)
    val message = textArgs.joinToString(" ").ifBlank {
        environment["JCLAW_MESSAGE"].orEmpty().ifBlank { readInput() }
    }.trim()
    require(message.isNotBlank()) { "Supply a message: ./jclaw skills [1-11] 'your text' (or JCLAW_MESSAGE/stdin)" }
    return SkillRewriteRequest(configuredLevel, message)
}
