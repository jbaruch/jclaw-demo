package com.jbaruch.jclaw.tui

import dev.tamboui.layout.Constraint
import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.toolkit.Toolkit.column
import dev.tamboui.toolkit.Toolkit.list
import dev.tamboui.toolkit.Toolkit.panel
import dev.tamboui.toolkit.Toolkit.row
import dev.tamboui.toolkit.Toolkit.text
import dev.tamboui.toolkit.Toolkit.textInput
import dev.tamboui.toolkit.app.ToolkitApp
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.tui.TuiConfig
import dev.tamboui.widgets.input.TextInputState

/**
 * Three-pane TUI for the j-claw demo, plus a ridiculous status line.
 *   CHAT   — the conversation between Baruch and j-claw
 *   TRACE  — the live agent trace (subgraph entries/exits, tool calls)
 *   STATUS — ridiculous "computing... combobulating..." while LLMs are in flight
 *   PROMPT — Baruch's input line (Enter submits)
 *
 * Mutations from background threads marshal via runner().runOnRenderThread per the
 * `jbaruch/tamboui` tile's render-thread-discipline rule.
 *
 * Wrapping: chat/trace inputs are pre-wrapped into multiple rows of <= JCLAW_WRAP
 * characters (default 88) BEFORE being added to the list. TamboUI's auto-wrap
 * primitives don't reflow inside a column reliably, and they fall back to ellipsis
 * truncation when they can't size a row. Pre-wrapping sidesteps the whole problem
 * — each chat/trace row is a plain text() element of safe width.
 */
class JclawTui(
    private val onSubmit: (String) -> Unit,
) : ToolkitApp() {

    private val chatLines: MutableList<String> = mutableListOf()
    private val traceLines: MutableList<String> = mutableListOf()
    private val promptInput = TextInputState()

    // Spinner state — refcounted so overlapping LLM calls don't clear too early.
    private var busyDepth: Int = 0
    private var statusText: String? = null

    /** Enable mouse capture so ListElement gets SCROLL_UP / SCROLL_DOWN wheel events. */
    override fun configure(): TuiConfig = TuiConfig.builder().mouseCapture(true).build()

    /** Start with focus on the prompt input so keystrokes go there, not into a list. */
    override fun onStart() {
        runner()?.focusManager()?.setFocus(PROMPT_ID)
    }

    fun chat(line: String) {
        val rows = wrap(line)
        runner()?.runOnRenderThread { chatLines.addAll(rows) }
    }

    fun trace(line: String) {
        val rows = wrap(line)
        runner()?.runOnRenderThread { traceLines.addAll(rows) }
    }

    /** Call when an LLM call starts. Rotates to a fresh ridiculous phrase. */
    fun startBusy() {
        runner()?.runOnRenderThread {
            busyDepth++
            statusText = "⏳ ${PHRASES.random()}"
        }
    }

    /** Call when an LLM call ends. Clears the status when no calls remain. */
    fun stopBusy() {
        runner()?.runOnRenderThread {
            busyDepth = (busyDepth - 1).coerceAtLeast(0)
            if (busyDepth == 0) statusText = null
        }
    }

    override fun render(): Element {
        // Pre-wrap stores already-sized rows, so each list item is one
        // already-wrapped row. ListElement.stickyScroll() keeps the view
        // pinned to the most recent line as new content arrives; scrollbar()
        // shows a visible track so the audience knows there's more above.
        val chatItems: Array<StyledElement<*>> = chatLines.takeLast(MAX_LINES)
            .map { text(it) as StyledElement<*> }.toTypedArray()
        val traceItems: Array<StyledElement<*>> = traceLines.takeLast(MAX_LINES)
            .map { text(it).fg(Color.CYAN) as StyledElement<*> }.toTypedArray()

        val statusLine: Element = statusText?.let { text(it).fg(Color.YELLOW).bold() }
            ?: text(" ")

        // Which element currently has keyboard focus — used to mark the active panel.
        val focusedId: String? = runner()?.focusManager()?.focusedId()
        fun borderFor(id: String): Color =
            if (id == focusedId) Color.GREEN else Color.GRAY

        return column(
            panel("CHAT",
                list(*chatItems)
                    .stickyScroll()
                    .scrollbar()
                    .selected(-1)                 // no item selected by default
                    .highlightSymbol("")          // no "> " prefix on the selected row
                    .highlightStyle(Style.EMPTY)  // no inverted-color highlight band
                    .id(CHAT_ID)
                    .focusable()
            ).rounded().borderColor(borderFor(CHAT_ID)).constraint(Constraint.fill()),
            panel("TRACE",
                list(*traceItems)
                    .stickyScroll()
                    .scrollbar()
                    .selected(-1)
                    .highlightSymbol("")
                    .highlightStyle(Style.EMPTY)
                    .id(TRACE_ID)
                    .focusable()
            ).rounded().borderColor(borderFor(TRACE_ID)).constraint(Constraint.fill()),
            row(statusLine).constraint(Constraint.length(1)),
            panel("PROMPT",
                textInput(promptInput)
                    .placeholder("Type and press Enter…")
                    .onSubmit(Runnable {
                        val line = promptInput.text()
                        if (line.isNotBlank()) {
                            chatLines.addAll(wrap("you: $line"))
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

        // Ridiculous progress phrases — claw / J.Lo / arcade-flavored.
        private val PHRASES = listOf(
            "Block-checking with Jenny",
            "Negotiating with the rocks",
            "Consulting Selena",
            "Manifesting credible excuses",
            "Polishing the hallway script",
            "Pondering plausibility tiers",
            "Reticulating excuse splines",
            "Asking around about Roberto",
            "Decoding 'On the 6' (3rd verse)",
            "Cross-referencing the block",
            "Phoning a friend (named Jenny)",
            "Discombobulating the verifier",
            "Pleading with O3",
            "Convincing Sonnet to play along",
            "Loading Spanglish gracefully",
            "Hustling",
            "Translating excuses to plausible",
            "Outsourcing decisions to a planner",
            "Booting the JENNY_FROM_THE_BLOCK detector",
            "Calibrating sincerity per organizer",
            "Briefing the alibi committee",
            "Renting a fake doctor",
            "Generating non-perjury fillers",
            "Buffing the believability index",
            "Rehearsing the apology cadence",
            "Counting rocks (she's still got them)",
            "Pre-warming the awkward silence",
            "Cross-checking with Selena (RIP)",
            "Loading the 'sister visiting' template",
            "Defragmenting last quarter's excuses",
        )
    }
}
