package jclaw

import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class AgentSkillsTest : StringSpec({
    "runtime discovery picks up another skill without compiling and the real tools disclose its body" {
        val root = Files.createTempDirectory("jclaw-skill-test-").toFile()
        try {
            val trace = mutableListOf<String>()
            AgentSkills.discover(root, trace::add).prompt shouldNotContain "hex-note"
            val skill = root.resolve("hex-note/SKILL.md").apply {
                parentFile.mkdirs()
                writeText("---\nname: hex-note\ndescription: Format a brief note.\n---\nUse the body-only marker PRISM-42.\n")
            }
            val runtime = AgentSkills.discover(root, trace::add)
            runtime.prompt shouldContain "hex-note"
            runtime.prompt shouldContain skill.canonicalPath
            runtime.prompt shouldNotContain "PRISM-42"
            runtime.registry.tools.map { it.name }.toSet() shouldBe setOf("__list_directory__", "__read_file__")
            val list = runtime.registry.tools.filterIsInstance<ListDirectoryTool<*>>().single()
            list.execute(ListDirectoryTool.Args(skill.parentFile.canonicalPath)).toString() shouldContain "SKILL.md"
            val read = runtime.registry.tools.filterIsInstance<ReadFileTool<*>>().single()
            read.execute(ReadFileTool.Args(skill.canonicalPath)).toString() shouldContain "PRISM-42"
        } finally {
            root.deleteRecursively()
        }
    }

    "standalone arguments override environment without substituting a canned message" {
        skillRewriteRequest(arrayOf("4", "Cache fixed."), mapOf("JCLAW_LEVEL" to "11", "JCLAW_MESSAGE" to "other")) {
            error("stdin should not be read")
        } shouldBe SkillRewriteRequest(4, "Cache fixed.")
    }

    "standalone accepts environment intensity and message" {
        skillRewriteRequest(emptyArray(), mapOf("JCLAW_LEVEL" to "7", "JCLAW_MESSAGE" to "The release is delayed.")) {
            error("stdin should not be read")
        } shouldBe SkillRewriteRequest(7, "The release is delayed.")
    }

    "standalone accepts multiline stdin and rejects missing input or invalid intensity" {
        skillRewriteRequest(arrayOf("2"), emptyMap()) { "First line.\nSecond line.\n" } shouldBe
            SkillRewriteRequest(2, "First line.\nSecond line.")
        shouldThrow<IllegalArgumentException> { skillRewriteRequest(emptyArray(), emptyMap()) { "" } }
        shouldThrow<IllegalArgumentException> { skillRewriteRequest(arrayOf("12", "text"), emptyMap()) { "" } }
        shouldThrow<IllegalArgumentException> { skillRewriteRequest(emptyArray(), mapOf("JCLAW_LEVEL" to "bad")) { "text" } }
    }
})
