package jclaw

import ai.koog.agents.core.agent.AIAgent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ai.koog.agents.chatMemory.feature.ChatMemoryConfig
import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.agents.chatMemory.feature.InMemoryChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import java.util.UUID

/** One process-local conversation. LongTermMemory separately persists successful sends. */
internal class Conversation(val systemPrompt: String) {
    val id: String = UUID.randomUUID().toString()
    private val history = InMemoryChatHistoryProvider()
    private val window = ConversationWindow(systemPrompt)

    fun configure(config: ChatMemoryConfig) {
        config.chatHistoryProvider = history
        config.addPreProcessor(window)
    }

    /** Commit normal turns through ChatMemory; a later ingestion failure must not publish a draft. */
    suspend fun <Output> run(agent: AIAgent<String, Output>, input: String): Output {
        val previous = history.load(id)
        val session = agent.createSession(id)
        return try {
            session.run(input)
        } catch (error: Throwable) {
            val messages = runCatching { session.context().llm.prompt.messages }.getOrDefault(emptyList())
            val current = messages.drop(messages.indexOfLast { it.isUserTurn() }.coerceAtLeast(0))
            val completedTools = completedToolExchanges(current)
            withContext(NonCancellable) {
                history.store(id, previous)
                if (completedTools.isNotEmpty()) {
                    user(input)
                    history.store(id, window.preprocess(history.load(id) + completedTools))
                    assistant("The request failed: ${error.message ?: error.javaClass.simpleName}. " +
                        "The tool results above still record what happened; completed actions were not undone.")
                }
            }
            throw error
        }
    }

    /** Application-only interactions, such as a send confirmation and its actual receipt. */
    suspend fun user(text: String) = append(Message.User(text, RequestMetaInfo.create(KoogClock.System)))
    suspend fun assistant(text: String) = append(Message.Assistant(text, ResponseMetaInfo.create(KoogClock.System)))

    private suspend fun append(message: Message) {
        history.store(id, window.preprocess(history.load(id) + message))
    }
}

/** Keep whole user turns, including tool call/result pairs, and the system/catalog prompt. */
internal class ConversationWindow(private val systemPrompt: String, private val turns: Int = 20) : ChatMemoryPreProcessor {
    override fun preprocess(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        val exchanges = messages.filterNot { it is Message.System }
        val starts = exchanges.indices.filter { exchanges[it].isUserTurn() }
        val first = starts.takeLast(turns).firstOrNull() ?: 0
        // Retrieval augments system messages each run. Keep the configured persona/catalog;
        // the next run retrieves fresh disk memory instead of accumulating old injections.
        val system = Message.System(systemPrompt, RequestMetaInfo.create(KoogClock.System))
        return listOf(system) + exchanges.drop(first)
    }
}

internal fun Message.isUserTurn(): Boolean = this is Message.User &&
    parts.any { it is MessagePart.Text } && parts.none { it is MessagePart.Tool.Result }

/** Preserve factual tool outcomes on failure, without retaining an unfinished assistant draft. */
private fun completedToolExchanges(messages: List<Message>): List<Message> = buildList {
    val pending = mutableMapOf<String, Message.Assistant>()
    messages.forEach { message ->
        when (message) {
            is Message.Assistant -> message.parts.filterIsInstance<MessagePart.Tool.Call>().forEach { call ->
                call.id?.let { pending[it] = Message.Assistant(call, message.metaInfo) }
            }
            is Message.User -> message.parts.filterIsInstance<MessagePart.Tool.Result>().forEach { result ->
                val call = pending.remove(result.id) ?: return@forEach
                if ((call.parts.single() as MessagePart.Tool.Call).tool == result.tool) {
                    add(call)
                    add(Message.User(result, message.metaInfo))
                }
            }
            else -> Unit
        }
    }
}
