package jclaw.round4

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

    override fun close() {
        procs.forEach { it.destroy() }
    }

    companion object {
        private val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath

        private val mocksDir: String = requireNotNull(System.getProperty("jclaw.mocks")) {
            "jclaw.mocks system property is not set - launch via the Gradle run task"
        }

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
