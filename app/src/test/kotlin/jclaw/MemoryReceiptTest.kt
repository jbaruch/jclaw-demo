package jclaw

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun call(id: String = "call-1") = Message.Assistant(
    MessagePart.Tool.Call(id, "sendDecline", """{"eventId":"release-review","message":"Please share the notes."}"""),
    ResponseMetaInfo.Empty,
)
private fun receipt(id: String = "call-1", delivered: Boolean = true, event: String = "release-review", error: Boolean = false): Message.User {
    val payload = buildJsonObject { put("delivered", delivered); put("eventId", event) }
    val envelope = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", payload.toString()) }) })
    }
    return Message.User(MessagePart.Tool.Result(id, "sendDecline", envelope.toString(), isError = error), RequestMetaInfo.Empty)
}

class MemoryReceiptTest : StringSpec({
    "only a matching successful organizer receipt becomes one memory" {
        val records = Memory.sentDeclines.extract(listOf(call(), receipt(), receipt()))
        records.size shouldBe 1
        records.single().content shouldContain "release-review"
        records.single().content shouldContain "Please share the notes."
    }
    "failed unconfirmed mismatched and out-of-order sends never become memories" {
        listOf(
            listOf(call()), listOf(receipt()), listOf(receipt(), call()),
            listOf(call(), receipt(id = "other-call")),
            listOf(call(), receipt(delivered = false)),
            listOf(call(), receipt(event = "other-event")),
            listOf(call(), receipt(error = true)),
            listOf(call(), Message.User(MessagePart.Tool.Result("call-1", "sendDecline", "Error: unavailable"), RequestMetaInfo.Empty)),
        ).forEach { Memory.sentDeclines.extract(it).size shouldBe 0 }
    }
    "ordinary rewrites and assistant claims of delivery never become sent history" {
        Memory.sentDeclines.extract(listOf(
            Message.User("Rewrite my release update.", RequestMetaInfo.Empty),
            Message.Assistant("Here is the rewritten update. I sent it.", ResponseMetaInfo.Empty),
        )).size shouldBe 0
    }
    "loaded receipts from a prior turn are not ingested again after an unrelated rewrite" {
        val firstTurn = listOf(Message.User("Send this decline.", RequestMetaInfo.Empty), call(), receipt())
        Memory.sentDeclines.extract(firstTurn).size shouldBe 1
        Memory.sentDeclines.extract(firstTurn + listOf(
            Message.User("rewrite in corporate-speak", RequestMetaInfo.Empty),
            Message.Assistant("An unsent rewrite.", ResponseMetaInfo.Empty),
        )).size shouldBe 0
    }

})
