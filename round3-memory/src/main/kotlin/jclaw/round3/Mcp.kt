package jclaw.round3

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.fromProcess
import java.io.File

/** Boots the two mock MCP servers as child processes and harvests their tools. */
object Mcp {
    private val javaBin: String =
        File(System.getProperty("java.home"), "bin/java").absolutePath

    private val mocksDir: String = requireNotNull(System.getProperty("jclaw.mocks")) {
        "jclaw.mocks system property is not set - launch via the Gradle run task"
    }

    private fun jar(name: String): String {
        val f = File(mocksDir, "$name.jar")
        require(f.exists()) { "missing ${f.absolutePath} - run: gradle :mocks:mcpJars" }
        return f.absolutePath
    }

    fun spawn(name: String): Process =
        ProcessBuilder(javaBin, "-jar", jar(name))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

    suspend fun registry(vararg servers: String): Pair<ToolRegistry, List<Process>> {
        val procs = servers.map { spawn(it) }
        val registries = procs.map { McpToolRegistryProvider.fromProcess(it) }
        return registries.reduce { a, b -> a + b } to procs
    }
}
