package jclaw

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.skills.discovery.discoverSkills
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import java.io.File

/** Runtime catalog and read-only tools shared by normal chat and the standalone runner. */
class AgentSkills private constructor(val prompt: String, val registry: ToolRegistry) {
    companion object {
        val EMPTY = AgentSkills("", ToolRegistry.EMPTY)

        suspend fun discover(
            root: File = File(System.getProperty("jclaw.skills") ?: System.getenv("JCLAW_SKILLS_ROOT") ?: "skills"),
            trace: (String) -> Unit = ::println,
        ): AgentSkills {
            val absoluteRoot = root.canonicalFile.absolutePath
            val discovered = discoverSkills(
                JVMFileSystemProvider.ReadOnly, listOf(absoluteRoot),
                warningLogger = { trace("skills: $it") },
            )
            trace("skills: " + discovered.joinToString { it.name }.ifEmpty { "none discovered in $absoluteRoot" })
            return AgentSkills(
                """
                Available skills are discovered from $absoluteRoot at startup.
                Select skills by their catalog descriptions when they fit the user's request.
                Before applying a skill, disclose it through the tools: list its directory
                with __list_directory__, then read its SKILL.md with __read_file__.
                Read any needed references from that skill directory. Apply the instructions
                to the user's supplied input; the catalog is not the full skill body.
                ${generateSkillsPrompt(discovered, SkillsPromptFormat.XML)}
                """.trimIndent(),
                ToolRegistry {
                    tool(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))
                    tool(ReadFileTool(JVMFileSystemProvider.ReadOnly))
                },
            )
        }
    }
}
