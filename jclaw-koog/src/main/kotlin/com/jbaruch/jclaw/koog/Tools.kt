package com.jbaruch.jclaw.koog

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.channels.Channel

/**
 * UserTools — local communication-with-Baruch tools.
 *
 * Calendar / attendee / organizer / sendExcuse come from the two mock MCP
 * servers (conference-mcp + contacts-mcp). "Memory" is NOT a tool here — Round
 * 3 installs Koog's ChatMemory feature, which preloads prior-excuse turns into
 * the LLM's context automatically. No searchPriorExcuses call needed.
 *
 * Constructor parameters are DI — the LLM never sees them. Tool annotations +
 * @LLMDescription are what the LLM consumes.
 */
@LLMDescription("Tools for communicating directly with Baruch (the user)")
class UserTools(
    private val outbound: (String) -> Unit,       // print to chat pane
    private val reactions: Channel<String>,       // y/n keystrokes from prompt pane
) : ToolSet {

    @Tool
    @LLMDescription("Ask Baruch a clarifying question and wait for his reply")
    suspend fun askBaruch(
        @LLMDescription("Question text — clear, one sentence")
        question: String,
    ): String {
        outbound("j-claw → Baruch: $question")
        outbound("(awaiting reply)")
        return reactions.receive()
    }

    @Tool
    @LLMDescription("Send Baruch a private status update — no response expected")
    fun pingBaruchPrivate(
        @LLMDescription("Status text — brief, terminal-friendly")
        text: String,
    ): String {
        outbound("j-claw → Baruch (private): $text")
        return "delivered"
    }

    @Tool
    @LLMDescription("Wait for Baruch to react with thumbs-up (y) or thumbs-down (n) on a staged action")
    suspend fun awaitReaction(
        @LLMDescription("What Baruch is reacting to — shown to him before the prompt")
        prompt: String,
    ): String {
        outbound("j-claw → Baruch: $prompt  [react with y / n]")
        return reactions.receive()
    }
}
