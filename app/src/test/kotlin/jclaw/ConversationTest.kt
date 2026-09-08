package jclaw

import ai.koog.agents.features.eventHandler.feature.handleEvents
import io.kotest.assertions.throwables.shouldThrow
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.model.MemoryRecord
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.DynamicPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.*
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.rag.base.TextDocument
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.rag.base.storage.SearchStorage
import ai.koog.rag.base.storage.search.SearchRequest
import ai.koog.rag.base.storage.search.SearchResult
import ai.koog.rag.base.storage.search.Score
import ai.koog.rag.base.storage.search.ScoreMetric
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.Flow
import java.nio.file.Files

internal class ScriptedExecutor(private val respond: (Prompt, List<ToolDescriptor>) -> Message.Assistant) : DynamicPromptExecutor() {
    override suspend fun resolveModel(model: LLModel, promptExecutorOperation: PromptExecutorOperation) = ResolvedModel(model)
    override suspend fun execute(prompt: Prompt, resolvedModel: ResolvedModel, tools: List<ToolDescriptor>) = respond(prompt, tools)
    override suspend fun executeMultipleChoices(prompt: Prompt, resolvedModel: ResolvedModel, tools: List<ToolDescriptor>): LLMChoice = error("Unexpected multiple choices")
    override fun executeStreaming(prompt: Prompt, resolvedModel: ResolvedModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("Unexpected streaming")
    override suspend fun moderate(prompt: Prompt, model: ResolvedModel): ModerationResult = error("Unexpected moderation")
    override suspend fun models() = listOf(Models.flash)
    override fun close() = Unit
}

class ConversationTest : StringSpec({
    "native ChatMemory carries draft and tool exchanges while fresh long-term facts reach the real executor" {
        val file = Files.createTempFile("jclaw-context-", ".txt").toFile().apply { writeText("reference-content") }
        val system = "Generic assistant. Runtime catalog: example-skill."
        val conversation = Conversation(system)
        val prompts = mutableListOf<Prompt>()
        var recalled = "OLD-MEMORY: used a family obligation"
        val storage = object : SearchStorage<TextDocument, SearchRequest> {
            override suspend fun search(request: SearchRequest, namespace: String?) =
                listOf(SearchResult<TextDocument>(MemoryRecord(recalled), Score(1.0, ScoreMetric.COSINE_SIMILARITY)))
        }
        val executor = ScriptedExecutor { prompt, _ ->
            prompts += prompt
            when (prompts.size) {
                1 -> Message.Assistant(MessagePart.Tool.Call("read-1", "__read_file__", """{"path":"${file.canonicalPath}"}"""), ResponseMetaInfo.Empty)
                2 -> Message.Assistant("Draft: The release is delayed. I will update you tomorrow.", ResponseMetaInfo.Empty)
                3 -> Message.Assistant("Rewritten draft.", ResponseMetaInfo.Empty)
                else -> Message.Assistant("The answer to the unrelated question is 42.", ResponseMetaInfo.Empty)
            }
        }
        val agent = AIAgent(promptExecutor = executor, llmModel = Models.flash, systemPrompt = system,
            toolRegistry = ToolRegistry { tool(ReadFileTool(JVMFileSystemProvider.ReadOnly)) },
        ) {
            install(ChatMemory) { conversation.configure(this) }
            install(LongTermMemory) { retrieval { this.storage = storage; searchStrategy = SimilaritySearchStrategy(topK = 5) } }
        }
        try {
            conversation.run(agent, "Draft a release update using the reference file.") shouldContain "Draft:"
            recalled = "FRESH-MEMORY: already used a customer escalation"
            conversation.run(agent, "rewrite in corporate-speak") shouldBe "Rewritten draft."
            val secondTurn = prompts[2]
            secondTurn.messages.joinToString { it.textContent() } shouldContain "Draft: The release is delayed. I will update you tomorrow."
            secondTurn.messages.joinToString { it.textContent() } shouldContain "rewrite in corporate-speak"
            val secondSystem = secondTurn.messages.filterIsInstance<Message.System>().joinToString { it.textContent() }
            secondSystem shouldContain system
            secondSystem shouldContain "FRESH-MEMORY"
            secondSystem shouldNotContain "OLD-MEMORY"
            secondTurn.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Call>().single().id shouldBe "read-1"
            secondTurn.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().single().id shouldBe "read-1"
            secondTurn.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().single().output shouldContain "reference-content"
            Memory.sentDeclines.extract(secondTurn.messages).size shouldBe 0
            conversation.run(agent, "What is six times seven?") shouldContain "42"
            prompts.last().messages.joinToString { it.textContent() } shouldContain "Rewritten draft."
            prompts.last().messages.joinToString { it.textContent() } shouldContain "What is six times seven?"
            Memory.sentDeclines.extract(prompts.last().messages).size shouldBe 0
        } finally { agent.close(); file.delete() }
    }

    "history window keeps system catalog and whole tool pairs with recent turns" {
        val user = { text: String -> Message.User(text, RequestMetaInfo.Empty) }
        val assistant = { text: String -> Message.Assistant(text, ResponseMetaInfo.Empty) }
        val call = Message.Assistant(MessagePart.Tool.Call("tool-2", "lookup", "{}"), ResponseMetaInfo.Empty)
        val result = Message.User(MessagePart.Tool.Result("tool-2", "lookup", "found"), RequestMetaInfo.Empty)
        val history = listOf(Message.System("stale augmented system", RequestMetaInfo.Empty), user("old"), assistant("old draft"), user("recent"), call, result, assistant("recent draft"))
        val kept = ConversationWindow("current persona and catalog", turns = 1).preprocess(history)
        kept.first().textContent() shouldBe "current persona and catalog"
        kept.drop(1) shouldBe history.drop(3)
    }
    "a completion failure rolls back unpublished text without losing the preceding conversation" {
        val conversation = Conversation("Generic assistant")
        val prompts = mutableListOf<Prompt>()
        var failCompletion = false
        val agent = AIAgent(
            promptExecutor = ScriptedExecutor { prompt, _ ->
                prompts += prompt
                Message.Assistant(if (failCompletion) "UNPUBLISHED-DRAFT" else "Earlier visible answer.", ResponseMetaInfo.Empty)
            },
            llmModel = Models.flash, systemPrompt = conversation.systemPrompt,
        ) {
            install(ChatMemory) { conversation.configure(this) }
            handleEvents { onAgentCompleted { if (failCompletion) error("Completion failed") } }
        }
        try {
            conversation.run(agent, "First question")
            failCompletion = true
            shouldThrow<IllegalStateException> { conversation.run(agent, "Failed question") }
            failCompletion = false
            conversation.run(agent, "Next question")
            prompts.last().messages.joinToString { it.textContent() } shouldContain "Earlier visible answer."
            prompts.last().messages.joinToString { it.textContent() } shouldNotContain "UNPUBLISHED-DRAFT"
            prompts.last().messages.joinToString { it.textContent() } shouldNotContain "Failed question"
        } finally { agent.close() }
    }

})
