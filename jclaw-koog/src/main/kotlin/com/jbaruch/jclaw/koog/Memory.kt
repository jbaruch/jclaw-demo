package com.jbaruch.jclaw.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo

/**
 * A read-only ChatHistoryProvider that returns the same pre-seeded prior-decline
 * conversation for every load. This is real Koog ChatMemory — not a tool the agent
 * calls, but conversation history the agent loads automatically before each LLM call.
 * The agent sees the prior turns as if it had said them.
 *
 * For persistence across runs, swap this with a SqliteChatHistoryProvider; the demo
 * just keeps it in-process so there's no file to manage.
 *
 * The provider takes optional callbacks so the TUI can surface "memory was loaded"
 * the same way it surfaces tool calls — otherwise memory preprocessing is invisible.
 */
class SeedMemoryProvider(
    private val onLoadTrace: (String) -> Unit = {},
    private val onLoadChat: (String) -> Unit = {},
) : ChatHistoryProvider {

    // Each turn is prefixed with its absolute date so the LLM doesn't confuse
    // historical declines with the current run. Without the date prefix, the model
    // tends to treat the most recent memory entry as "today's" decline.
    private val seed: List<Message> = listOf(
        userTurn("[2025-06-19] Decline JNation 2025 speaker dinner (organizer Roberto Cortez)."),
        assistantTurn("[2025-06-19] Done — JNation 2025 declined with the EMERGENCY_MEETING excuse to Roberto Cortez."),
        userTurn("[2025-10-09] Decline Devoxx BE 2025 speaker dinner (organizer Stephan Janssen)."),
        assistantTurn("[2025-10-09] Done — Devoxx BE 2025 declined with the FAMILY_OBLIGATION excuse to Stephan Janssen."),
        userTurn("[2026-04-15] Decline Spring I/O 2026 speaker dinner (organizer Sergi Almar)."),
        assistantTurn("[2026-04-15] Done — Spring I/O 2026 declined with the HOTEL_ISSUE excuse to Sergi Almar."),
    )

    private val priorDeclineCount: Int = seed.count { it is Message.Assistant }

    override suspend fun store(conversationId: String, messages: List<Message>) {
        // Read-only seed — the demo doesn't persist live turns, so the pre-seed
        // survives across runs. Swap in a real provider to capture live turns.
    }

    override suspend fun load(conversationId: String): List<Message> {
        onLoadTrace("   ↪ ChatMemory.load → $priorDeclineCount prior declines")
        onLoadChat("🧠 j-claw recalled prior declines → $priorDeclineCount entries from memory")
        return seed
    }

    private fun userTurn(text: String) = Message.User(text, RequestMetaInfo.Empty)
    private fun assistantTurn(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)
}
