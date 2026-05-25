package com.jbaruch.jclaw.koog

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.channels.Channel

/**
 * Three ToolSets sliced by access pattern per SPEC.md §5.
 *
 * Constructor parameters are DI — the LLM never sees them. Tool annotations + @LLMDescription
 * are what the LLM consumes.
 */

// -----------------------------------------------------------------------------
// UserTools — communication with Baruch only
// -----------------------------------------------------------------------------

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
        // For the demo: in the TUI this would block on the prompt pane; console build prints + waits
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

// -----------------------------------------------------------------------------
// ReadTools — read-only access to memory and (optionally) MCP-served calendar/organizer
// -----------------------------------------------------------------------------

@LLMDescription("Read-only tools — calendar look-up, organizer info, and prior-excuse memory")
class ReadTools(
    private val priorDeclines: List<PriorDecline>,
) : ToolSet {

    @Tool
    @LLMDescription("Returns all decline-related history j-claw has stored about Baruch — what was said to which organizer at which past event")
    fun searchPriorExcuses(): List<PriorDecline> {
        return priorDeclines
    }
}

data class PriorDecline(
    val eventName: String,
    val organizerName: String,
    val flavorUsed: ExcuseFlavor,
    val date: String,
) {
    // Manual serialization-friendly toString — LLM reads this through getter calls
    override fun toString(): String = "PriorDecline(event=\"$eventName\", organizer=\"$organizerName\", flavor=$flavorUsed, date=$date)"
}

// Calendar and organizer tools come from MCP servers (calendar-mcp + organizer-mcp).
// j-claw-koog imports them via `McpToolRegistryProvider.fromProcess(...)` in Main.kt.
// They're NOT defined here as local ToolSets — that's the whole point of MCP.

// -----------------------------------------------------------------------------
// WriteTools — mutate state, send messages (currently a no-op shell for testing)
// -----------------------------------------------------------------------------

// WriteTools' real implementations live in the calendar-mcp and organizer-mcp servers
// (createCalendarEvent, sendDecline). No local WriteTools needed — same MCP setup as ReadTools'
// calendar / organizer surfaces. Both classes of access go through MCP.
//
// If you need write-side LOCAL tools (e.g., logging to a file, ringing a bell), declare a
// `class LocalWriteTools(...) : ToolSet { ... }` here.
