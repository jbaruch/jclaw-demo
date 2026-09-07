package jclaw

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.metadata.McpServerInfo
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.defaultStdioTransport
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.File

/**
 * Boots the mock MCP servers and keeps the client handles.
 *
 * Keeping the clients matters: the agent gets the tools it needs to PLAN,
 * but the one irreversible action - actually contacting a human - is called
 * by the application, not chosen by a model.
 */
class Mcp private constructor(
    val registry: ToolRegistry,
    private val clients: Map<String, Client>,
    private val procs: List<Process>,
) : AutoCloseable {

    suspend fun call(server: String, tool: String, args: Map<String, Any?>): String {
        val client = requireNotNull(clients[server]) { "no such MCP server: $server" }
        val result = client.callTool(tool, args)
        return result?.toString() ?: "(no result)"
    }

    /**
     * Kills the child processes. Deliberately does NOT await `Client.close()`:
     * Protocol.close() does not return once the transport is gone, and it blocks a
     * thread rather than suspending, so even withTimeoutOrNull cannot get past it.
     *
     * The transport also leaves a non-daemon reader thread alive, so callers follow
     * this with exitProcess. A CLI that finished its work must not hang the terminal.
     */
    override fun close() {
        // destroyForcibly + waitFor, not destroy(): these children inherit our stderr
        // (that is where the [calendar-mcp] trace lines come from). If we exit while
        // they are still dying, they hold that pipe open and whoever owns it - Gradle -
        // waits on it long after the demo finished.
        procs.forEach { it.destroyForcibly() }
        procs.forEach { runCatching { it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) } }
    }

    companion object {
        private val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath

        /** Gradle passes this; the start script falls back to the repo layout. */
        private val mocksDir: String =
            System.getProperty("jclaw.mocks") ?: "mocks/build/libs"

        suspend fun boot(vararg servers: String): Mcp {
            val procs = mutableListOf<Process>()
            val clients = mutableMapOf<String, Client>()
            var registry = ToolRegistry.EMPTY

            for (name in servers) {
                val jar = File(mocksDir, "$name.jar")
                require(jar.exists()) { "missing ${jar.absolutePath} - run: gradle :mocks:mcpJars" }

                val proc = ProcessBuilder(javaBin, "-jar", jar.absolutePath)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                procs += proc

                val client = Client(Implementation("j-claw", "1.0.0"))
                client.connect(with(McpToolRegistryProvider) { defaultStdioTransport(proc) })
                clients[name] = client

                registry += McpToolRegistryProvider.fromClient(
                    mcpClient = client,
                    serverInfo = McpServerInfo(command = name),
                )
            }
            return Mcp(registry, clients, procs)
        }
    }
}
