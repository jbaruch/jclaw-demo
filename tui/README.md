# tui/

Shared **TamboUI** three-pane shell, used by every round (`./jclaw N`). `JclawTui.kt`
owns the shell; `ChatMarkdown.kt` renders model replies through native Markdown.
`TraceStopwatch.kt` records phase durations. Both sides of the talk can embed the
module, so the on-stage interface is the same.

## Layout

    j-claw   MCP   MEMORY                            <- header: app identity, one badge per feature
    FLOW  identify ✓ → deploy ● → verify · ⇄ refine · <- round 4 only: the pipeline, live
    ┌ CHAT ───────────────────────────────────────┐   <- you and j-claw
    └─────────────────────────────────────────────┘
    ┌ TRACE ──────────────────────────────────────┐   <- model calls, tool calls, MCP server log
    └─────────────────────────────────────────────┘      lines, memory reads/writes, subgraph entry/exit
    ⏳ status line while a model call is in flight
    ┌ PROMPT ─────────────────────────────────────┐   <- Enter submits; the TUI echoes "you: …" itself
    └─────────────────────────────────────────────┘

## API

- `JclawTui(onSubmit, title, features, flow)` - `title` is the app identity (default
  `j-claw`); `features` become header badges (cyan, magenta, yellow, in order); `flow`
  is stage names and connector arrows. Keep round/stage labels out of the title so
  they do not duplicate the feature badges.
- `chat(line, ChatKind)`, `trace(line, TraceKind)`, `startBusy()` / `stopBusy()`,
  `stage(name, StageState)`, `resetFlow()` - safe from any thread. Calls made before
  the runner exists are queued and replayed in `onStart`.
- `traceStage(stage, provider, TraceStageState)` gives each phase invocation one
  stopwatch row. `STARTED (running 20s)` updates in place each second; completion,
  failure, or cancellation freezes the elapsed time. Repeated phases get new rows.
  `finishTraceStages(terminalState)` closes any unfinished phases and clears the busy
  status when a request ends. The refresh task stops when idle or when the TUI closes.
- `JclawTui.quietStdStreams(path)` - call it first thing in `main`. Anything a library
  prints to stdout or stderr while the TUI owns the terminal lands on screen and stays
  there. `restoreStdStreams()` before reporting a fatal error.

## Render-thread discipline

All UI mutation happens on the render thread via `runOnRenderThread`; the public
methods above marshal for you. Model replies use TamboUI 0.4.0's native CommonMark
and GFM renderer: paragraphs are normal weight, with Markdown headings, emphasis,
lists, links, and code rendered as terminal styles. The chat roles keep their colors.

Whole model replies are parsed before wrapping to `JCLAW_WRAP` columns (default 88),
then converted into styled single-row list items. This preserves formatting across
wrap boundaries and keeps scrolling through long replies correct. Other chat and
trace text remains pre-wrapped plain text. The persistent chat and trace lists keep
their focus, scroll position, and sticky-scroll behavior across redraws.
