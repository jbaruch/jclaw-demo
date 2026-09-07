package jclaw

import ai.koog.agents.core.tools.annotations.LLMDescription
import jclaw.domain.DeclineDeployment
import kotlinx.serialization.Serializable

/** What the user asked for. Not every message is a job. */
@Serializable
@LLMDescription("Whether the user wants an obligation dealt with, or is just talking")
public data class ClassifiedInput(
    @property:LLMDescription("EXCUSE_REQUEST when they want out of something, CHAT otherwise")
    val intent: Intent,
    @property:LLMDescription("The user's message, echoed verbatim")
    val userMessage: String,
)

@Serializable
public enum class Intent { EXCUSE_REQUEST, CHAT }

/**
 * Both branches of the graph converge here, so the agent can stay alive between
 * prompts instead of exiting after one excuse.
 */
public sealed interface JclawResult {
    public data class ExcuseSent(val deployment: DeclineDeployment) : JclawResult
    public data class ChatReply(val text: String) : JclawResult
}
