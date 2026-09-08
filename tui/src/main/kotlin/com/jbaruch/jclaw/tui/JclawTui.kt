package com.jbaruch.jclaw.tui

import dev.tamboui.layout.Constraint
import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.text.Line
import dev.tamboui.text.Span
import dev.tamboui.text.Text
import dev.tamboui.toolkit.Toolkit.column
import dev.tamboui.toolkit.Toolkit.list
import dev.tamboui.toolkit.Toolkit.panel
import dev.tamboui.toolkit.Toolkit.row
import dev.tamboui.toolkit.Toolkit.richText
import dev.tamboui.toolkit.Toolkit.text
import dev.tamboui.toolkit.Toolkit.textInput
import dev.tamboui.toolkit.app.ToolkitApp
import dev.tamboui.toolkit.app.ToolkitRunner
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.tui.TuiConfig
import dev.tamboui.tui.event.TickEvent
import dev.tamboui.widgets.input.TextInputState
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/** What kind of CHAT line — drives the color. */
enum class ChatKind { JCLAW, YOU, TOOL_RESULT, OK, ERR }

/** A pipeline stage's state in the FLOW row. */
enum class StageState { PENDING, ACTIVE, DONE, FAILED }

/** What kind of TRACE line — drives the color. */
enum class TraceKind { SUBGRAPH_START, SUBGRAPH_END, TOOL_CALL, LLM, RUNNING, ERROR }

/**
 * Three-pane TUI for the j-claw demo, plus a ridiculous status line.
 *   HEADER — app identity, and badges for the features it has (MCP, MEMORY, ...)
 *   CHAT   — the conversation between Baruch and j-claw
 *   TRACE  — the live agent trace (subgraph entries/exits, tool calls)
 *   STATUS — ridiculous "computing... combobulating..." while LLMs are in flight
 *   PROMPT — Baruch's input line (Enter submits)
 *
 * Mutations from background threads marshal via runner().runOnRenderThread per the
 * `jbaruch/tamboui` tile's render-thread-discipline rule.
 *
 * Wrapping: model replies are parsed as whole Markdown messages and rendered into
 * styled rows of JCLAW_WRAP columns (default 88). Other chat and trace lines are
 * pre-wrapped. Keeping one row per list item preserves line-by-line scrolling;
 * TamboUI 0.4.0's list does not offset the content of a partly scrolled tall item.
 */
class JclawTui(
    private val onSubmit: (String) -> Unit,
    /** App identity shown in the header; feature names belong in [features]. */
    private val title: String = "j-claw",
    /** One badge per feature this round has, in order: the deck lights them up round by round. */
    private val features: List<String> = emptyList(),
    /** The pipeline as stage names and connector arrows, e.g. identify, →, deploy, →, verify, ⇄, refine. */
    private val flow: List<String> = emptyList(),
) : ToolkitApp() {

    private val stageStates = HashMap<String, StageState>()

    /** Light a stage up in the FLOW row: call from the subgraph start/complete events. */
    fun stage(name: String, state: StageState) = onRenderThread { stageStates[name] = state }

    /** Every prompt is a fresh run through the pipeline. */
    fun resetFlow() = onRenderThread { stageStates.clear() }

    private val chatLines: MutableList<Line> = mutableListOf()
    private val traceLines: MutableList<TraceEntry> = mutableListOf()
    private val activeTraceStages = mutableMapOf<Pair<String, String>, TraceStopwatch>()
    private var traceRefresh: ToolkitRunner.ScheduledAction? = null
    private var startedNanos = System.nanoTime()
    private var traceTick = 0L
    @Volatile private var stopped = false
    private val promptInput = TextInputState()

    // Persistent ListElement instances. ListElement holds its scroll position
    // (and "user-scrolled-away" flag) in a private final ListState field —
    // building a fresh ListElement every render frame wipes the user's manual
    // scroll. Keeping the SAME instance across renders and just updating its
    // items via .elements(...) preserves the scroll state.
    private val chatListElement: ListElement<*> = list()
        .stickyScroll().scrollbar()
        .selected(-1).highlightSymbol("").highlightStyle(Style.EMPTY)
        .id(CHAT_ID).focusable()
    private val traceListElement: ListElement<*> = list()
        .stickyScroll().scrollbar()
        .selected(-1).highlightSymbol("").highlightStyle(Style.EMPTY)
        .id(TRACE_ID).focusable()

    // Spinner state — refcounted so overlapping LLM calls don't clear too early.
    private var busyDepth: Int = 0
    private var statusText: String? = null

    /** Enable mouse capture so ListElement gets SCROLL_UP / SCROLL_DOWN wheel events. */
    override fun configure(): TuiConfig = TuiConfig.builder().mouseCapture(true).build()

    // The agent starts before run() has created the runner, and the first things it
    // says (servers ready, tools discovered, memory loaded) used to be dropped on the
    // floor by `runner()?.`. Queue them; onStart drains the queue on the render thread.
    private val pending = ConcurrentLinkedQueue<Runnable>()

    private fun onRenderThread(block: () -> Unit) {
        if (stopped) return
        val r = runner()
        val guarded = Runnable { if (!stopped) block() }
        if (r == null) {
            pending.add(guarded)
            if (stopped) pending.remove(guarded)
        } else r.runOnRenderThread(guarded)
    }

    /** Focus the prompt so keystrokes go there, not into a list; replay anything said before we existed. */
    override fun onStart() {
        stopped = false
        startedNanos = System.nanoTime()
        traceTick = 0
        runner()?.focusManager()?.setFocus(PROMPT_ID)
        while (true) (pending.poll() ?: break).run()
    }

    override fun onStop() {
        stopped = true
        finishTraceStagesOnRenderThread(TraceStageState.CANCELLED, System.nanoTime())
        pending.clear()
    }

    fun chat(line: String, kind: ChatKind = ChatKind.JCLAW) {
        val rows = chatRows(line, kind)
        onRenderThread { chatLines.addAll(rows) }
    }

    fun trace(line: String, kind: TraceKind = TraceKind.TOOL_CALL) {
        val rows = wrap(line).map { TraceMessage(it, kind) }
        onRenderThread { traceLines.addAll(rows) }
    }

    /** Update one invocation in place; another STARTED for the same phase gets a fresh row. */
    fun traceStage(stage: String, provider: String, state: TraceStageState) {
        // Capture at the event, not when a busy render thread eventually applies it.
        val eventNanos = System.nanoTime()
        onRenderThread {
            val key = stage to provider
            if (state == TraceStageState.STARTED) {
                activeTraceStages.remove(key)?.finish(TraceStageState.CANCELLED, eventNanos)
                val stopwatch = TraceStopwatch(stage, provider, eventNanos)
                activeTraceStages[key] = stopwatch
                traceLines.add(stopwatch)
                if (traceRefresh == null) {
                    val r = runner()
                    traceRefresh = r?.scheduleRepeating({
                        r.runOnRenderThread {
                            if (!stopped && activeTraceStages.isNotEmpty()) redrawTrace()
                        }
                    }, Duration.ofSeconds(1))
                }
            } else {
                activeTraceStages.remove(key)?.finish(state, eventNanos)
                if (activeTraceStages.isEmpty()) stopTraceRefresh()
            }
            redrawTrace()
        }
    }

    /** Finish any phases left open when a request returns, fails, or is cancelled. */
    fun finishTraceStages(state: TraceStageState) {
        require(state != TraceStageState.STARTED) { "A terminal phase outcome is required" }
        val eventNanos = System.nanoTime()
        onRenderThread {
            finishTraceStagesOnRenderThread(state, eventNanos)
            redrawTrace()
        }
    }

    private fun finishTraceStagesOnRenderThread(state: TraceStageState, eventNanos: Long) {
        activeTraceStages.forEach { (key, stopwatch) ->
            stopwatch.finish(state, eventNanos)
            stageStates[key.first] = if (state == TraceStageState.COMPLETED) StageState.DONE else StageState.FAILED
        }
        activeTraceStages.clear()
        stopTraceRefresh()
        busyDepth = 0
        statusText = null
    }

    private fun stopTraceRefresh() {
        traceRefresh?.cancel()
        traceRefresh = null
    }

    private fun redrawTrace() {
        // In TamboUI 0.4.0 UiRunnable alone does not redraw. A tick goes through the
        // normal Toolkit event path, which renders on the render thread even when idle.
        runner()?.tuiRunner()?.dispatch(
            TickEvent(++traceTick, Duration.ofNanos((System.nanoTime() - startedNanos).coerceAtLeast(0))),
        )
    }

    /** Call when an LLM call starts. Rotates to a fresh ridiculous phrase. */
    fun startBusy() {
        onRenderThread {
            busyDepth++
            statusText = "⏳ ${PHRASES.random()}"
        }
    }

    /** Call when an LLM call ends. Clears the status when no calls remain. */
    fun stopBusy() {
        onRenderThread {
            busyDepth = (busyDepth - 1).coerceAtLeast(0)
            if (busyDepth == 0) statusText = null
        }
    }

    private fun chatRows(line: String, kind: ChatKind): List<Line> {
        if (kind == ChatKind.JCLAW) return markdownLines(line, WRAP)
        val style = when (kind) {
            ChatKind.JCLAW       -> Style.EMPTY.fg(Color.BLUE)
            ChatKind.OK          -> Style.EMPTY.fg(Color.BLUE).bold()
            ChatKind.YOU         -> Style.EMPTY.fg(Color.GREEN).bold()
            ChatKind.TOOL_RESULT -> Style.EMPTY.fg(Color.YELLOW)
            ChatKind.ERR         -> Style.EMPTY.fg(Color.RED).bold()
        }
        return wrap(line).map { Line.from(Span.styled(it, style)) }
    }

    private fun traceText(line: String, kind: TraceKind): StyledElement<*> = when (kind) {
        TraceKind.SUBGRAPH_START -> text(line).fg(Color.MAGENTA).bold()  // new phase opens
        TraceKind.SUBGRAPH_END   -> text(line).fg(Color.GREEN)            // phase result
        TraceKind.TOOL_CALL      -> text(line).fg(Color.CYAN)             // every tool invocation
        TraceKind.LLM            -> text(line).fg(Color.GRAY)             // every model call
        TraceKind.RUNNING        -> text(line).fg(Color.YELLOW)           // elapsed phase time, in place
        TraceKind.ERROR          -> text(line).fg(Color.RED)              // failed or cancelled phase
    }

    private fun badge(label: String, bg: Color): Element =
        text(" $label ").fg(Color.BLACK).bg(bg).bold().constraint(Constraint.length(label.length + 2))

    private fun gap(): Element = text(" ").constraint(Constraint.length(1))

    /** The handoff, live: the active stage is yellow, finished ones green, the rest gray. */
    private fun flowRow(): Element? {
        if (flow.isEmpty()) return null
        val cells = ArrayList<Element>()
        cells += badge("FLOW", Color.GREEN)
        for (item in flow) {
            cells += gap()
            cells += if (item in CONNECTORS) {
                text(item).fg(Color.GRAY).constraint(Constraint.length(item.length))
            } else {
                val (mark, color) = when (stageStates[item]) {
                    StageState.ACTIVE -> "●" to Color.YELLOW
                    StageState.DONE -> "✓" to Color.GREEN
                    StageState.FAILED -> "✘" to Color.RED
                    StageState.PENDING, null -> "·" to Color.GRAY
                }
                val label = "$item $mark"
                val cell = text(label).fg(color).constraint(Constraint.length(label.length))
                if (stageStates[item] == StageState.ACTIVE) cell.bold() else cell
            }
        }
        cells += text("").constraint(Constraint.fill())
        return row(*cells.toTypedArray()).constraint(Constraint.length(1))
    }

    /** App identity, then one lit badge per feature: MCP cyan, MEMORY magenta, WORKFLOW yellow. */
    private fun header(): Element {
        val cells = ArrayList<Element>()
        cells += badge(title, Color.GREEN)
        features.forEachIndexed { i, f -> cells += gap(); cells += badge(f, BADGE_COLORS[i % BADGE_COLORS.size]) }
        cells += text("").constraint(Constraint.fill())
        return row(*cells.toTypedArray()).constraint(Constraint.length(1))
    }

    override fun render(): Element {
        // Update the persistent lists' items each frame WITHOUT rebuilding the
        // list elements themselves — that keeps the user's scroll position alive.
        val chatItems: Array<StyledElement<*>> = chatLines.takeLast(MAX_LINES)
            .map { richText(Text.from(it)) }.toTypedArray()
        val nowNanos = System.nanoTime()
        val traceItems: Array<StyledElement<*>> = traceLines.takeLast(MAX_LINES)
            .map { traceText(it.text(nowNanos), it.kind) }.toTypedArray()
        chatListElement.elements(*chatItems)
        traceListElement.elements(*traceItems)

        val statusLine: Element = statusText?.let { text(it).fg(Color.YELLOW).bold() }
            ?: text(" ")

        // Which element currently has keyboard focus — used to mark the active panel.
        val focusedId: String? = runner()?.focusManager()?.focusedId()
        fun borderFor(id: String): Color =
            if (id == focusedId) Color.GREEN else Color.GRAY

        return column(
            header(),
            *listOfNotNull(flowRow()).toTypedArray(),
            panel("CHAT", chatListElement)
                .rounded().borderColor(borderFor(CHAT_ID)).constraint(Constraint.fill()),
            panel("TRACE", traceListElement)
                .rounded().borderColor(borderFor(TRACE_ID)).constraint(Constraint.fill()),
            row(statusLine).constraint(Constraint.length(1)),
            panel("PROMPT",
                textInput(promptInput)
                    .placeholder("Type and press Enter…")
                    .onSubmit(Runnable {
                        val line = promptInput.text()
                        if (line.isNotBlank()) {
                            chatLines.addAll(chatRows("you: $line", ChatKind.YOU))
                            onSubmit(line)
                            promptInput.clear()
                        }
                    })
                    .id(PROMPT_ID)
                    .focusable()
            ).rounded().borderColor(borderFor(PROMPT_ID)).constraint(Constraint.length(3)),
        )
    }

    companion object {
        private const val MAX_LINES = 400
        private const val CHAT_ID = "chat-list"
        private const val TRACE_ID = "trace-list"
        private const val PROMPT_ID = "jclaw-prompt"
        private val BADGE_COLORS = listOf(Color.CYAN, Color.MAGENTA, Color.YELLOW)
        private val CONNECTORS = setOf("→", "⇄", "↺", "->", "<->")

        private val originalOut: PrintStream = System.out
        private val originalErr: PrintStream = System.err

        /**
         * Whatever a library prints to stdout or stderr while the TUI owns the terminal
         * (kotlin-logging's init line on stdout, SLF4J's "no providers" notice on stderr,
         * a stack trace) lands ON the screen and stays there, because the renderer only
         * repaints cells it changed. Send both to a file instead - the TUI draws through
         * JLine's own terminal stream, not System.out. Nothing is dropped: read the file.
         */
        fun quietStdStreams(path: Path) {
            val log = PrintStream(FileOutputStream(path.toFile(), true), true, Charsets.UTF_8)
            System.setOut(log)
            System.setErr(log)
        }

        /** Undo [quietStdStreams], for the one message that must reach a human: the TUI died. */
        fun restoreStdStreams() {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        /** Wrap width in columns. Override with JCLAW_WRAP env var (floor 20). */
        private val WRAP: Int = (System.getenv("JCLAW_WRAP")?.toIntOrNull() ?: 88).coerceAtLeast(20)

        /**
         * Greedy word-wrap. Returns one or more rows, each at most [width] characters.
         * Honors embedded newlines as paragraph breaks; normalizes \r and tabs.
         * Hard-breaks single words longer than [width] so a runaway token can't overflow.
         */
        fun wrap(text: String, width: Int = WRAP): List<String> {
            val normalized = text.replace("\r", "").replace("\t", "  ")
            val paragraphs = normalized.split("\n")
            val out = ArrayList<String>(paragraphs.size)

            for (paragraph in paragraphs) {
                if (paragraph.isEmpty()) {
                    out.add("")
                    continue
                }
                var current = StringBuilder()
                for (rawWord in paragraph.split(" ")) {
                    var remaining = rawWord
                    // Hard-break anything longer than width.
                    while (remaining.length > width) {
                        if (current.isNotEmpty()) {
                            out.add(current.toString())
                            current = StringBuilder()
                        }
                        out.add(remaining.substring(0, width))
                        remaining = remaining.substring(width)
                    }
                    if (remaining.isEmpty()) continue
                    val needed = if (current.isEmpty()) remaining.length else current.length + 1 + remaining.length
                    if (needed > width) {
                        out.add(current.toString())
                        current = StringBuilder(remaining)
                    } else {
                        if (current.isEmpty()) current.append(remaining)
                        else current.append(' ').append(remaining)
                    }
                }
                if (current.isNotEmpty()) out.add(current.toString())
            }
            return out
        }

        // Status-line phrases while a model call is in flight. The scenario's own jokes:
        // a mandatory AI training, a touchy People Ops organizer, and a growing pile of
        // excuses already used on her.
        private val PHRASES = listOf(
            "Reading the calendar, again",
            "Checking how touchy Dana is today",
            "Auditing last quarter's excuses",
            "Cross-referencing burned excuses",
            "Counting excuses already used on Dana",
            "Checking memory for what we told her last time",
            "Scheduling a scheduling conflict",
            "Staging an alibi on the calendar",
            "Booking a dentist who does not exist",
            "Drafting a conflict that cannot move",
            "Making the meeting look real",
            "Calibrating sincerity for People Ops",
            "Estimating the HR_WILL_NOTICE risk",
            "Sorting excuses by plausibility tier",
            "Choosing between honest and employed",
            "Compressing the truth, deniably",
            "Translating into corporate register",
            "Writing the hallway script",
            "Practicing the apologetic tone",
            "Consulting the tool registry",
            "Waking up the critic",
            "Arguing with the critic",
            "Losing the argument with the critic",
            "Asking Gemini to keep it short",
            "Passing the AI proficiency test, ironically",
            "Attending the training so you don't have to",
            "Being verifiably already proficient",
            "Escalating to nobody in particular",
            "Reading the room through a stream",
            "Waiting for People Ops to notice",
        )
    }
}
