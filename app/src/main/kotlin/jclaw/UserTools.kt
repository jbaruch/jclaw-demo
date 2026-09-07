package jclaw

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.channels.Channel

/**
 * The third capability axis: talking to the USER.
 *
 * Read tools touch nothing. Write tools change Baruch's world but nobody else
 * sees it. Comms tools reach the organizer and are irreversible. These reach
 * Baruch — and which phase gets them is the whole argument.
 *
 * `deploy` does not get these. It commits to a plan in silence. `verify` does,
 * so the critic is the phase that surfaces and asks. That contrast is the point;
 * without a user-facing tool at all, "deploy cannot contact a human" is true but
 * says nothing.
 *
 * Constructor parameters are DI. The LLM never sees them - only the annotations.
 */
@LLMDescription("Tools for talking to Baruch, the user, while you work")
class UserTools(
    private val outbound: (String) -> Unit,
    private val reactions: Channel<String>,
) : ToolSet {

    @Tool
    @LLMDescription("Ask Baruch a clarifying question and wait for his answer")
    suspend fun askBaruch(
        @LLMDescription("The question. One sentence, no preamble.")
        question: String,
    ): String {
        outbound("j-claw → you: $question")
        return reactions.receive()
    }

    @Tool
    @LLMDescription("Tell Baruch something without waiting for a reply")
    fun pingBaruch(
        @LLMDescription("What he needs to know")
        message: String,
    ): String {
        outbound("j-claw → you: $message")
        return "delivered"
    }

    @Tool
    @LLMDescription("Show Baruch the staged plan and wait for approval before anything leaves the building")
    suspend fun awaitApproval(
        @LLMDescription("A one-line summary of what is about to be sent")
        summary: String,
    ): String {
        outbound("j-claw → you: $summary")
        outbound("(approve? y / n)")
        val answer = reactions.receive().trim().lowercase()
        return if (answer.startsWith("y")) "APPROVED" else "REJECTED: $answer"
    }
}
