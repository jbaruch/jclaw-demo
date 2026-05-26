package com.jbaruch.jclaw.koog

import ai.koog.agents.longtermmemory.model.MemoryRecord
import ai.koog.agents.longtermmemory.storage.InMemoryRecordStorage
import ai.koog.rag.base.TextDocument
import ai.koog.rag.base.storage.SearchStorage
import ai.koog.rag.base.storage.search.SearchRequest
import ai.koog.rag.base.storage.search.SearchResult

/**
 * Pre-seeded LongTermMemory fact store of Baruch's prior excuses.
 *
 * Replaces the earlier ChatHistoryProvider approach. Facts now live on the fact
 * channel — TextDocument records retrieved by Koog 1.0's LongTermMemory feature
 * and augmented into the system prompt before each LLM call — rather than being
 * impersonated as Message.User / Message.Assistant turns with a [YYYY-MM-DD]
 * prefix workaround. The date-prefix hack was a tell that the old channel was
 * wrong: facts aren't conversation, and the model only avoided treating them as
 * "today's task" because every turn was labeled with its date. With LongTermMemory
 * the LLM sees the facts in a clearly labeled "relevant context" block — no
 * impersonation, no prefix hack.
 *
 * For persistence across runs, swap [InMemoryRecordStorage] for one of the
 * persistent backends in the rag-vector module set; the demo keeps it in-process
 * so there's no file to manage.
 *
 * The decorator emits trace/chat events on every search so the TUI can surface
 * "memory was consulted" the same way it surfaces tool calls. Retrieval fires
 * before every LLM call (RAG-by-default), so the audience sees one event per
 * subgraph LLM round-trip — that's intentional, shows what LongTermMemory does.
 */
suspend fun priorExcusesStorage(
    onSearchTrace: (Int) -> Unit = {},
    onSearchChat: (Int) -> Unit = {},
): SearchStorage<TextDocument, SearchRequest> {
    val backing = InMemoryRecordStorage()
    backing.add(
        listOf(
            MemoryRecord(
                id = "prior-excuse-jnation-2025",
                content = "On 2025-06-19 Baruch used the EMERGENCY_MEETING excuse flavor to decline the JNation 2025 speaker dinner organized by Roberto Cortez.",
            ),
            MemoryRecord(
                id = "prior-excuse-devoxx-2025",
                content = "On 2025-10-09 Baruch used the FAMILY_OBLIGATION excuse flavor to decline the Devoxx BE 2025 speaker dinner organized by Stephan Janssen.",
            ),
            MemoryRecord(
                id = "prior-excuse-spring-io-2026",
                content = "On 2026-04-15 Baruch used the HOTEL_ISSUE excuse flavor to decline the Spring I/O 2026 speaker dinner organized by Sergi Almar.",
            ),
        ),
    )
    return object : SearchStorage<TextDocument, SearchRequest> by backing {
        override suspend fun search(request: SearchRequest, namespace: String?): List<SearchResult<TextDocument>> {
            val results = backing.search(request, namespace)
            onSearchTrace(results.size)
            onSearchChat(results.size)
            return results
        }
    }
}
