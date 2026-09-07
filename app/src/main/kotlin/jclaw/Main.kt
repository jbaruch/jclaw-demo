package jclaw

import ai.koog.agents.cli.CliAIAgent
import ai.koog.agents.cli.transport.CliTransport
import jclaw.domain.Scenario
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * ROUND 1 - "a chatbot in five minutes", and it does not cost you a penny.
 *
 * One factory call, same as any Koog agent - except `apiKey = null`, which makes
 * CliAIAgent authenticate through whatever Claude Code subscription is already
 * logged in on this machine. No key, no per-token bill, no procurement.
 *
 * Everything under it is a read-print loop, which is the honest shape of a chatbot.
 * Two things to notice while you talk to it, because rounds 2 and 3 are about
 * exactly these:
 *
 *   - it cannot DO anything. It will cheerfully tell you it put something in your
 *     calendar. It has no calendar, and no tools of ours at all.
 *   - it does not remember. Ask it what you just said.
 */
private val NO_TOOLS = """
    {"permissions":{"deny":["Bash","Read","Edit","Write","WebFetch","WebSearch","Glob","Grep","Task","NotebookEdit"]}}
""".trimIndent()

fun main(): Unit = runBlocking {
    // Coding agents read their working directory. Pen it in.
    val pen = Files.createTempDirectory("jclaw-round1").toFile()
        .apply { deleteOnExit() }.absolutePath

    val jclaw = CliAIAgent.claude(
        transport = CliTransport.default(),
        apiKey = null,                 // <- the whole point: use the subscription
        workspace = pen,
        systemPrompt = Scenario.SYSTEM_PROMPT,
        // NOT optional, and not decoration. Claude Code inherits the MCP servers
        // configured on this machine - calendar, mail, travel - and `workspace` only
        // scopes the FILESYSTEM. Without this, asking this "chatbot" to get you out of
        // a meeting makes it read your real calendar and write a real event to it.
        // Which it did, to mine, before these flags existed.
        //
        //   --strict-mcp-config : use only MCP from --mcp-config. We pass none, so
        //                         no calendar, no mail, no travel. This is the leak.
        //   --settings <json>   : deny the built-ins too.
        //
        // Do NOT reach for --tools or --disallowedTools here: both are variadic, so
        // they swallow the prompt and the run dies with "No result event found".
        // --settings takes a single argument and cannot eat anything.
        additionalFlags = listOf(
            "--strict-mcp-config",
            "--settings",
            NO_TOOLS,
        ),
    )

    println("j-claw, running on a Claude subscription. No API key.")
    println("Say something. (blank line or ctrl-D to quit)\n")

    while (true) {
        print("you: ")
        val line = readlnOrNull()?.trim()
        if (line.isNullOrEmpty()) break
        println()
        println("j-claw: " + jclaw.run(line).content)
        println()
    }
    println("bye.")
}
