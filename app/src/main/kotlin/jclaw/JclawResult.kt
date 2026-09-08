package jclaw

import ai.koog.agents.core.tools.annotations.LLMDescription
import jclaw.domain.DeclineDeployment
import kotlinx.serialization.Serializable

/** What the user asked for. Not every message is a job. */
@Serializable
@LLMDescription("Whether the user requests the decline planning workflow or ordinary assistant help")
public data class ClassifiedInput(
    @property:LLMDescription("EXCUSE_REQUEST for a request to plan getting out of an obligation; CHAT for editing, skills, questions and discussion")
    val intent: Intent,
    @property:LLMDescription("The user's message, echoed verbatim")
    val userMessage: String,
)

@Serializable
public enum class Intent { EXCUSE_REQUEST, CHAT }

/** Only ReadyToSend can reach the application's human confirmation and send path. */
@Serializable
public sealed interface JclawResult {
    @Serializable
    public data class ReadyToSend(val deployment: DeclineDeployment) : JclawResult
    @Serializable
    public data class Blocked(val reason: String, val deployment: DeclineDeployment? = null) : JclawResult
    @Serializable
    public data class ChatReply(val text: String) : JclawResult
}

/** The application owns the external action. Neither rejection nor a human 'no' can send. */
suspend fun deliverApproved(
    result: JclawResult,
    confirm: suspend (DeclineDeployment) -> Boolean,
    send: suspend (DeclineDeployment) -> Unit,
): Boolean {
    if (result !is JclawResult.ReadyToSend || !confirm(result.deployment)) return false
    send(result.deployment)
    return true
}
