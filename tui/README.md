# tui/

Shared **TamboUI** three-pane shell, used by every round (`./jclaw N`). One module, one
file: `JclawTui.kt`. Both sides of the talk can embed it, so the on-stage interface is
the same.

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
- `JclawTui.quietStdStreams(path)` - call it first thing in `main`. Anything a library
  prints to stdout or stderr while the TUI owns the terminal lands on screen and stays
  there. `restoreStdStreams()` before reporting a fatal error.

## Render-thread discipline

All UI mutation happens on the render thread via `runOnRenderThread`; the public
methods above marshal for you. Chat and trace text is pre-wrapped to `JCLAW_WRAP`
columns (default 88), because TamboUI's auto-wrap clips with an ellipsis inside a
column.
