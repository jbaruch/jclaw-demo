package com.jbaruch.jclaw.tui

/** A phase invocation's lifecycle; terminal states freeze its elapsed time. */
enum class TraceStageState { STARTED, COMPLETED, FAILED, CANCELLED }

internal sealed interface TraceEntry {
    val kind: TraceKind
    fun text(nowNanos: Long): String
}

internal data class TraceMessage(val message: String, override val kind: TraceKind) : TraceEntry {
    override fun text(nowNanos: Long): String = message
}

/** Mutable only on the TUI's render thread. Each invocation owns a separate row. */
internal class TraceStopwatch(
    private val stage: String,
    private val provider: String,
    private val startedNanos: Long,
) : TraceEntry {
    private var state = TraceStageState.STARTED
    private var finishedNanos: Long? = null

    fun finish(outcome: TraceStageState, nowNanos: Long) {
        require(outcome != TraceStageState.STARTED) { "A terminal phase outcome is required" }
        if (finishedNanos != null) return
        state = outcome
        finishedNanos = nowNanos
    }

    override val kind: TraceKind
        get() = when (state) {
            TraceStageState.STARTED -> TraceKind.RUNNING
            TraceStageState.COMPLETED -> TraceKind.SUBGRAPH_END
            TraceStageState.FAILED, TraceStageState.CANCELLED -> TraceKind.ERROR
        }

    override fun text(nowNanos: Long): String {
        val seconds = ((finishedNanos ?: nowNanos) - startedNanos).coerceAtLeast(0) / 1_000_000_000
        val elapsed = if (state == TraceStageState.STARTED) "running ${seconds}s" else "${seconds}s"
        return "$stage · $provider · $state ($elapsed)"
    }
}
