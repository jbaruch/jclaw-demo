package jclaw

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.fromProcess
import java.io.File

/** Boots the two mock MCP servers as child processes and harvests their tools. */
object Mcp {
    private val javaBin: String =
        File(System.getProperty("java.home"), "bin/java").absolutePath

    private val mocksDir: String =
        System.getProperty("jclaw.mocks") ?: "mocks/build/libs"

    private fun jar(name: String): String {
        val f = File(mocksDir, "$name.jar")
        require(f.exists()) { "missing ${f.absolutePath} - run: gradle :mocks:mcpJars" }
        return f.absolutePath
    }

    /**
     * stderr is piped, not inherited: the TUI routes the servers' own trace lines into
     * its TRACE pane, and the stdout front end prints them. Pumped on a daemon thread,
     * so no child ever shares our descriptor.
     */
    fun spawn(name: String, onStderr: (String) -> Unit = System.err::println): Process {
        val proc = ProcessBuilder(javaBin, "-jar", jar(name)).start()
        Thread { proc.errorStream.bufferedReader().useLines { it.forEach(onStderr) } }
            .also { it.isDaemon = true; it.name = "$name-stderr" }.start()
        return proc
    }

    suspend fun registry(vararg servers: String, onStderr: (String) -> Unit = System.err::println): Pair<ToolRegistry, List<Process>> {
        val procs = servers.map { spawn(it, onStderr) }
        val registries = procs.map { McpToolRegistryProvider.fromProcess(it) }
        return registries.reduce { a, b -> a + b } to procs
    }
}
