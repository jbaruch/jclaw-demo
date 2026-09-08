# tui/

Shared **TamboUI** TUI shell. Both Koog and LangChain4j Agentic apps embed this module so the on-stage interface is visually identical between the two sides.

## Layout — three panes

```
┌─ j-claw ─────────────────────────────────────────────────┐
│  CHAT                                                    │  ← user types, agent replies here
├──────────────────────────────────────────────────────────┤
│  TRACE                                                   │  ← subtask names, tool calls, models, durations
├──────────────────────────────────────────────────────────┤
│  PROMPT                                                  │  ← input + keyboard y/n reaction
└──────────────────────────────────────────────────────────┘
```

- **CHAT** — message thread between user and j-claw
- **TRACE** — driven by Koog's `handleEvents { ... }` or LC4J's event hooks. Every subtask entry/exit + every tool call adds a line
- **PROMPT** — keyboard input. `y` = 👍, `n` = 👎. The agent's `awaitReaction` tool blocks on this keystroke

## Header color

- Baruch's Koog side: green
- Viktor's LC4J side: yellow

## Render-thread discipline

Per `jbaruch/tamboui` tile rule `render-thread-discipline`: all UI mutations on the render thread; from agent callbacks use `runOnRenderThread { ... }`.
