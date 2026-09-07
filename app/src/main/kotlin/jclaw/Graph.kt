package jclaw

import ai.koog.agents.core.agent.asMermaidDiagram
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import java.io.File

/**
 * Emit the pipeline diagram FROM the pipeline.
 *
 * The JNation cut showed a hand-drawn diagram of this graph, and the honest
 * caveat on stage was "my diagram was static". This one is not: Koog walks the
 * actual strategy object - the same one round 4 just ran - and emits a Mermaid
 * state diagram. Change an edge and the picture changes, because there is no
 * picture, only the graph.
 *
 *   gradle :app:graph
 *
 * Writes pipeline.mmd, which IntelliJ renders in the Markdown/Mermaid preview.
 */
fun main(): Unit = runBlocking {
    Mcp.boot("calendar-mcp", "organizer-mcp").use { mcp ->
        val strategy = jclawStrategy(
            mcp = mcp,
            naive = false,
            cliCritic = System.getenv("JCLAW_CRITIC") == "cli",
        )
        val diagram = strategy.asMermaidDiagram()
        val out = File(System.getProperty("jclaw.graph.out") ?: "pipeline.mmd")
        out.writeText(diagram)
        println(diagram)
        System.err.println("\n[graph] written to ${out.absolutePath} — open it in IntelliJ for the rendered view")
        mcp.close()   // destroys the child processes; synchronous, returns
        // The MCP stdio transport leaves a non-daemon reader thread alive that survives
        // both Client.close() and Process.destroy(), so the JVM will not exit on its own.
        // This is a CLI that has finished its job; on stage a hung terminal after a
        // successful run reads as a broken demo. Exit deliberately.
        exitProcess(0)
    }
}
