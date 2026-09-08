package jclaw

import ai.koog.agents.longtermmemory.ingestion.extraction.DocumentExtractor
import ai.koog.agents.longtermmemory.model.MemoryRecord
import ai.koog.embeddings.base.Embedder
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.rag.base.TextDocument
import ai.koog.rag.base.files.DocumentProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.rag.base.storage.SearchStorage
import ai.koog.rag.base.storage.WriteStorage
import ai.koog.rag.base.storage.search.SearchRequest
import ai.koog.rag.base.storage.search.SearchResult
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import ai.koog.rag.vector.storage.TextFileDocumentEmbeddingStorage
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlinx.serialization.json.jsonPrimitive

/**
 * j-claw's memory is a directory.
 *
 *     memory/documents/<id>   one story per file: what j-claw told Dana, and when
 *     memory/vectors/<id>     that file's embedding - a cache, rebuilt when missing
 *
 * Koog's file-backed vector store does the reading, writing and cosine search;
 * Gemini does the embedding. The three prior declines are committed text files,
 * not code, and every decline j-claw sends is filed next to them - so the next
 * run, restart or no restart, has one more story to avoid.
 */
class Memory private constructor(
    private val root: Path,
    private val store: TextFileDocumentEmbeddingStorage<TextDocument, Path>,
    private val trace: (String) -> Unit,
) : SearchStorage<TextDocument, SearchRequest>, WriteStorage<TextDocument> {

    /** Retrieval runs before every LLM call; the trace says so once, not once per tool round-trip. */
    private var lastTraced: String? = null

    override suspend fun search(request: SearchRequest, namespace: String?): List<SearchResult<TextDocument>> {
        require(request is SimilaritySearchRequest) { "memory answers similarity searches only, not $request" }
        val hits = store.search(request, namespace)
        val line = "  <- memory: " + hits.joinToString { it.document.id ?: "?" }.ifEmpty { "nothing yet" }
        if (line != lastTraced) trace(line)
        lastTraced = line
        return hits
    }

    override suspend fun add(documents: List<TextDocument>, namespace: String?): List<String> {
        val ids = store.add(documents, namespace)
        ids.forEach { trace("  => memory: wrote $root/documents/$it") }
        return ids
    }

    override suspend fun update(documents: Map<String, TextDocument>, namespace: String?): List<String> =
        store.update(documents, namespace)

    companion object {
        /** A file is a memory: the name is the id, the body is the story. Dotfiles are not memories. */
        private object Files : DocumentProvider<Path, TextDocument> {
            override suspend fun document(path: Path): TextDocument? =
                if (path.isRegularFile() && !path.name.startsWith(".")) MemoryRecord(path.readText(), path.name) else null

            override suspend fun text(document: TextDocument): CharSequence = document.content
        }

        /** Where it lives. `./jclaw` runs from the repo root; Gradle passes the absolute path. */
        val dir: Path = Path(System.getProperty("jclaw.memory") ?: "memory")

        suspend fun open(embedder: Embedder, root: Path = dir, trace: (String) -> Unit = ::println): Memory {
            val store = TextFileDocumentEmbeddingStorage(embedder, Files, JVMFileSystemProvider.ReadWrite, root)
            val memory = Memory(root, store, trace)
            // Documents are the truth, vectors are a cache: embed whatever has no vector yet.
            val vectors = root.resolve("vectors").createDirectories()
            val unindexed = root.resolve("documents").listDirectoryEntries()
                .filter { vectors.resolve(it.name).notExists() }
                .mapNotNull { Files.document(it) }
            if (unindexed.isNotEmpty()) {
                memory.update(unindexed.associateBy { requireNotNull(it.id) })
                trace("  == memory: embedded ${unindexed.size} stories from $root/documents")
            }
            return memory
        }

        /** A story in the shape the seeds use, for a round that knows which flavor it used. */
        fun story(event: String, organizer: String, flavor: String, message: String): MemoryRecord = MemoryRecord(
            "${LocalDate.now()}: Declined $event, run by $organizer. Excuse flavor used: $flavor. Told them: \"$message\"\n"
        )

        /**
         * What a run leaves behind: what j-claw actually told the organizer - the sendDecline
         * calls it made. Not the ask, and not its own account of itself: a run that sends
         * nothing remembers nothing. No flavor either - round 3 has no domain model to name one.
         */
        val whatJclawToldDana = DocumentExtractor { messages ->
            messages.filterIsInstance<Message.Assistant>()
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool.Call>()
                .filter { it.tool == "sendDecline" }
                .map { call ->
                    val eventId = call.argsJson.getValue("eventId").jsonPrimitive.content
                    val message = call.argsJson.getValue("message").jsonPrimitive.content
                    MemoryRecord("${LocalDate.now()}: Declined event $eventId. Told the organizer: \"$message\"\n")
                }
        }
    }
}
