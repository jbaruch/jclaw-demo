# j-claw — IntelliJ IDEA Conf 2026

The Koog side of *Codepocalypse Now: LangChain4j vs JetBrains Koog*.

j-claw is a personal agent that gets you out of **Basic AI Proficiency Training
(Mandatory)**, run by Dana from People Ops. It does not merely decline: it stages
a calendar event to back the story up, calibrates to how much scrutiny Dana
applies, and writes you a hallway script for when she asks about it tomorrow.

Built against **Koog 1.2.0**, released 2026-08-28.

## Modules

| Branch | Round | What it adds |
|---|---|---|
| `round1` | 1 | One `AIAgent(...)` factory call |
| `round2` | 2 | Tool registry from two MCP servers. Acts — and reuses a burned excuse |
| `round3` | 3 | Koog `LongTermMemory` over a directory on disk: three committed prior declines in `memory/documents/`, and every decline it sends filed next to them. Stops repeating itself |
| `round4` | 4 | `subgraphWithTask` / `subgraphWithVerification`, tools sliced by capability, critic, approval node |

Every branch is the same `app` module with the same file at
`app/src/main/kotlin/jclaw/Main.kt` — only its contents change, so on stage the code
appears to evolve rather than being four prepared copies. Shared across all branches:
`domain` (types), `mocks` (the two MCP servers), and on `round4` also `tui` and
`skills/`.

## The argument

Rounds 1–3 are one agent having one conversation. Round 4 is typed subtasks handing
each other **data**:

```
                 +--> chatReply ------------------> ChatReply
                 |
start --> classify
                 |
                 +--> identify --> deploy --> verify --(approved)--> approve --> ExcuseSent
                                                 ^         |
                                                 +- refine +  (rejected, with feedback)
```

Three things the shape buys you, none of which are prompt engineering:

1. **Tool slicing has consequences.** `deploy` has no communication tools at all, so
   it cannot contact a human even if it decides to.
2. **The critic is a different phase on a different model.** You do not let the model
   that drafted the excuse decide whether the excuse is good.
3. **Approval is a node, sending is not in the graph.** `approve` blocks on a real
   human — not a tool the model may decide to skip — and only then does the
   application call `sendDecline`.

Set `JCLAW_NAIVE=1` to strip the typed constraint out of the handoff. Same pipeline,
same models, same tools — poorer data. Watch it reach for an excuse it already used.

## Running

```bash
./jclaw 3                           # any round, in the three-pane TUI, sentence already asked
./jclaw plain                       # the same round on stdout (paste the sentence)
./jclaw skills 11                   # corporate-speak at intensity 11
./jclaw graph                       # pipeline.mmd, generated from the live strategy
```

**Do not use `gradle run`** — it never returns. The app exits, the mocks exit, Gradle
waits forever. `./jclaw` builds with Gradle and then runs the installed binary, which
is what the JNation build did for the same reason.

See `RUNBOOK.md` for stage commands and timings.
