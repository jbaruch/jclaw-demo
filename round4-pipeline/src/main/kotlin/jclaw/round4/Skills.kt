package jclaw.round4

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.skills.discovery.discoverSkills
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import kotlinx.coroutines.runBlocking

/**
 * The Agent Skills flourish.
 *
 * Koog 1.2 shipped a `skills` module implementing the Agent Skills spec ten
 * days before this talk. Nothing here is hard-coded: the agent discovers a
 * skill on disk, reads it with file tools, and applies it. Drop another
 * SKILL.md next to it and the agent can do that too, with no recompile.
 *
 * The message j-claw's critic approved is honest and readable. That is a
 * problem, because it is going to People Ops.
 */
fun main() = runBlocking {
    val apiKey = requireNotNull(System.getenv("GOOGLE_API_KEY")) { "GOOGLE_API_KEY is not set" }
    val skillsRoot = requireNotNull(System.getProperty("jclaw.skills")) {
        "jclaw.skills is not set - launch via: gradle :round4-pipeline:runSkills"
    }

    val discovered = discoverSkills(JVMFileSystemProvider.ReadOnly, listOf(skillsRoot))
    println("skills discovered: " + discovered.joinToString { it.name })

    val skillsPrompt = generateSkillsPrompt(discovered, SkillsPromptFormat.XML)

    val approved = System.getenv("JCLAW_MESSAGE") ?: DEFAULT_APPROVED_MESSAGE
    println("\n--- what the critic approved (level 0) ---\n$approved")

    val agent = AIAgent(
        promptExecutor = simpleGoogleAIExecutor(apiKey),
        systemPrompt = """
            You are j-claw's outbound editor. Before using a skill, disclose it:
            list the skill directory and read the SKILL.md so the audience can see
            exactly what you are about to apply. Then apply it.

            $skillsPrompt
        """.trimIndent(),
        llmModel = GoogleModels.Gemini3_1Pro_Preview,
        toolRegistry = ToolRegistry {
            tool(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))
            tool(ReadFileTool(JVMFileSystemProvider.ReadOnly))
        },
    ) {
        handleEvents { onToolCallStarting { println("      tool  ${it.toolName}") } }
    }

    val level = System.getenv("JCLAW_LEVEL") ?: "11"
    println("\n--- applying corporate-speak at intensity $level ---\n")
    println(
        agent.run(
            "The skills root is $skillsRoot. Apply the corporate-speak skill at " +
                "intensity $level to the message below. Return only the rewritten message.\n\n$approved",
        ),
    )
}

private const val DEFAULT_APPROVED_MESSAGE =
    "Hi Dana, thank you for organising this training. Building AI agents is my " +
        "day job and I am presenting a live conference talk on exactly this topic " +
        "that afternoon, so I am going to skip the basic module. I appreciate you " +
        "putting the resources together for the team."
