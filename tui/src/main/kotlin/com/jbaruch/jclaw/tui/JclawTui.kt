package com.jbaruch.jclaw.tui

import dev.tamboui.layout.Constraint
import dev.tamboui.style.Color
import dev.tamboui.toolkit.Toolkit.column
import dev.tamboui.toolkit.Toolkit.panel
import dev.tamboui.toolkit.Toolkit.row
import dev.tamboui.toolkit.Toolkit.text
import dev.tamboui.toolkit.Toolkit.textInput
import dev.tamboui.toolkit.app.ToolkitApp
import dev.tamboui.toolkit.element.Element
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

    fun chat(line: String) {
        runner()?.runOnRenderThread { chatLines.add(line) }
    }

    fun trace(line: String) {
        runner()?.runOnRenderThread { traceLines.add(line) }
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
        val chatChildren = chatLines.takeLast(MAX_LINES).map { text(it) as Element }.toTypedArray()
        val traceChildren = traceLines.takeLast(MAX_LINES).map { text(it).dim() as Element }.toTypedArray()

        val statusLine: Element = statusText?.let { text(it).fg(Color.YELLOW).bold() }
            ?: text(" ").dim()

        return column(
            panel("CHAT", column(*chatChildren)).rounded()
                .constraint(Constraint.fill()),
            panel("TRACE", column(*traceChildren)).rounded()
                .constraint(Constraint.fill()),
            row(statusLine).constraint(Constraint.length(1)),
            panel("PROMPT",
                textInput(promptInput)
                    .placeholder("Type and press Enter…")
                    .onSubmit(Runnable {
                        val line = promptInput.text()
                        if (line.isNotBlank()) {
                            chatLines.add("you: $line")
                            onSubmit(line)
                            promptInput.clear()
                        }
                    })
            ).rounded().constraint(Constraint.length(3)),
        )
    }

    companion object {
        private const val MAX_LINES = 200

        // Ridiculous progress phrases — claw / J.Lo / arcade-flavored.
        private val PHRASES = listOf(
            "Block-checking with Jenny",
            "Negotiating with the rocks",
            "Consulting Selena",
            "Manifesting credible excuses",
            "Polishing the hallway script",
            "Pondering plausibility tiers",
            "Reticulating excuse splines",
            "Asking around about Filipe",
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
