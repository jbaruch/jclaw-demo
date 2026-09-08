package jclaw

/** Sending needs an exact affirmative. Other substantive replies are the next request. */
internal sealed interface SendReply {
    data object Send : SendReply
    data object Hold : SendReply
    data class FollowUp(val text: String) : SendReply
}

internal fun sendReply(answer: String): SendReply {
    val text = answer.trim()
    if (text.lowercase() in setOf("send", "y", "yes")) return SendReply.Send
    return when (text.lowercase().trimEnd('.', '!')) {
        "", "n", "no", "hold", "cancel", "stop", "no thanks", "no thank you", "don't send", "do not send" -> SendReply.Hold
        else -> SendReply.FollowUp(text)
    }
}

/** A queued follow-up is appended by the next agent run, exactly once. */
internal suspend fun Conversation.confirmSend(answer: String, queue: (String) -> Unit): Boolean =
    when (val reply = sendReply(answer)) {
        SendReply.Send -> { user(answer); true }
        SendReply.Hold -> { user(answer); false }
        is SendReply.FollowUp -> { queue(reply.text); false }
    }
