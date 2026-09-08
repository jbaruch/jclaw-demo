# j-claw — IntelliJ IDEA Conf 2026

The Koog side of *Codepocalypse Now: LangChain4j vs JetBrains Koog*.

j-claw is a personal agent that gets you out of **Basic AI Proficiency Training
(Mandatory)**, run by Dana from People Ops. It does not merely decline: it stages
a calendar event to back the story up, calibrates to how much scrutiny Dana
applies, and writes you a hallway script for when she asks about it tomorrow.

Built against **Koog 1.2.0**, released 2026-08-28.

## Modules

| Module | Round | What it adds |
|---|---|---|
| `domain` | — | `ExcuseFlavor`, `DeclineRequest`, `DeclineDeployment`, `DeclineCritique` |
| `mocks` | — | `calendar-mcp` + `organizer-mcp`, real stdio MCP servers (Kotlin SDK 0.11.1) |
| `round1-chatbot` | 1 | One `AIAgent(...)` factory call |
| `round2-tools-mcp` | 2 | Tool registry from two MCP servers. Acts — and reuses a burned excuse |
| `round3-memory` | 3 | Koog `LongTermMemory` over a directory on disk: three committed prior declines in `memory/documents/`, and every decline it sends filed next to them. Stops repeating itself |
| `round4-pipeline` | 4 | `subgraphWithTask` / `subgraphWithVerification`, tools sliced by capability |
| `skills/` | 4b | An Agent Skill (`corporate-speak`) discovered off disk at runtime |

## The argument

Rounds 1–3 are one agent having one conversation. Round 4 is typed subtasks handing
each other **data**:

```
identify --> deploy --> verify --(approved)--> done
               ^           |
               +-- refine <+  (rejected, with feedback)
```

Three things the shape buys you, none of which are prompt engineering:

1. **Tool slicing has consequences.** `deploy` has no communication tools at all, so
   it cannot contact a human even if it decides to.
2. **The critic is a different phase on a different model.** You do not let the model
   that drafted the excuse decide whether the excuse is good.
3. **The irreversible action is not in the graph.** The agent produces an approved
   plan; the application sends it, after a human says yes.

Set `JCLAW_NAIVE=1` to strip the typed constraint out of the handoff. Same pipeline,
same models, same tools — poorer data. Watch it reach for an excuse it already used.

See `RUNBOOK.md` for stage commands.
