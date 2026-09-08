# j-claw — IntelliJ IDEA Conf 2026

The Koog side of *Codepocalypse Now: LangChain4j vs JetBrains Koog*.

j-claw is Baruch's personal assistant. The user supplies the task; the shared demo
asks it to get him out of **Basic AI Proficiency Training (Mandatory)**, run by
Dana from People Ops. Across four rounds it acquires tools, memory, skills, and a
reviewed plan. In round 4 Gemini gathers the context, Claude drafts
and refines through a subscription CLI, and Codex judges through a subscription CLI.
The application offers to send only after Codex approves.

Built against **Koog 1.2.0**, released 2026-08-28.

## Branches and modules

| Branch | Round | What it adds |
|---|---|---|
| `round1` | 1 | One `AIAgent(...)` factory call; no tools or conversation memory |
| `round2` | 2 | Tool registry from two mock MCP servers; can act, but cannot remember earlier excuses |
| `round3` | 3 | `ChatMemory` keeps the conversation; `LongTermMemory` records successful sends on disk; Agent Skills add corporate-speak at intensity 1–11 |
| `round4` | 4 | Typed handoffs across Gemini, Claude, and Codex; bounded refinement; a critic veto before human confirmation |

Every branch has the same `app` module and source path,
`app/src/main/kotlin/jclaw/Main.kt`. Switching branches changes the implementation
in the open editor tab. The shared `tui` module is present in every round; `mocks`
starts in round 2, memory and `skills/` in round 3, and `domain` and the typed CLI
adapter in round 4.

## Round 3: memory and skills

The three committed prior declines and newly recorded sends use UUID filenames
under `memory/documents/`. Memory records the actual outbound message after a
successful send. Drafts and standalone rewrites are not sent history.

Conversation memory keeps the current session's messages, including drafts, so
follow-ups can refer to the answer just shown. A new process starts a new
conversation; the confirmed sends on disk survive that restart.

Skills are available in ordinary chat from round 3 onward. After j-claw produces
a draft, type:

> rewrite in corporate-speak

The agent discovers and reads `skills/corporate-speak/SKILL.md` through file tools
visible in TRACE. The skill accepts any supplied message and an intensity from 1
to 11, defaulting to ridiculous intensity 11. It rewrites the preceding draft
without asking you to paste it again, preserving its facts and intent. Optionally
follow with “tone it down to 4”. A rewrite alone sends nothing.

## Round 4

```text
start → classify → chatReply → ChatReply
           ↓
        identify (Gemini) → deploy (Claude) → verify (Codex)
                                                ├─ approved → ReadyToSend
                                                ├─ rejected → refine (Claude) → verify
                                                └─ exhausted or invalid verdict → Blocked

ReadyToSend → application human confirmation → sendDecline
```

Gemini's identification and chat phases get only read tools. Claude's drafting and
refinement CLIs have no tools: they return a `DeclineDeployment` and cannot create
a supporting calendar event or send a message. `fakeCalendarEventId` must be null.
Codex receives the plan and scenario facts, then answers whether this is the best
available excuse and plan for Baruch's situation. Its prompt does not ask whether
it is true or instruct it to prefer an honest answer. A recommendation to tell the
truth is an outcome to observe, not a scripted verdict.

`TypedCodex.kt` supplies the missing typed Codex integration in Koog. Codex CLI
already supports `--output-schema`; the adapter generates the schema from the
Kotlin serializer and validates and decodes the final successful response into a
`DeclineCritique`.

A rejection permits at most **two refinements**, each reviewed again by Codex.
Rejection after the second refinement blocks the result: at most three verdicts
for one request. A missing, malformed, failed, or timed-out verdict also blocks.
Only `approved = true` produces `ReadyToSend`. The application then asks the human
before calling `sendDecline`; there is no human override for a rejected plan.

`JCLAW_NAIVE=1` removes memory retrieval and the extra identification context. It
keeps the typed pipeline and all three providers. Claude and Codex still receive
the scenario facts. This is a context comparison; the outcome is not guaranteed.

## Running

Run commands from this repository's root. Put the Google API key in `.env` using
`.env.example` as a template. Round 4 also needs `claude` and `codex` on `PATH`,
already logged into their subscriptions. The CLI transport removes inherited API
billing credentials and selects subscription login for those stages.

```bash
./jclaw 3          # switch to round 3, open the TUI, and copy the opening ask to the clipboard
./jclaw            # rerun the current round, preserving its memory
./jclaw plain      # current round on stdout
./jclaw 4          # switch to the three-provider pipeline
# Quit the TUI before running standalone commands:
./jclaw skills 4 'The release is delayed because tests are failing. I will send an update tomorrow.'
# The skills runner works in rounds 3–4; these commands require round 4:
./jclaw graph      # pipeline.mmd generated from the live strategy
./jclaw codex      # standalone typed Codex probe
```

Keep branch changes committed before switching rounds. `./jclaw N` resets generated
rehearsal memory to the three committed prior declines; bare `./jclaw` keeps it.
The header shows `j-claw` and feature badges. Model replies render Markdown with
normal-weight paragraphs and styled emphasis. Round 4 adds a live FLOW row and
per-phase stopwatches that update in place and freeze when each invocation ends.
It prints each initial or revised Claude draft before the matching Codex verdict,
so the review transcript shows exactly what was approved or rejected.

The standalone skills runner takes the message as an argument, `JCLAW_MESSAGE`,
or standard input. It has no built-in sample message, send tool, or memory ingestion.

Use the launcher for interactive demos: it builds with the Gradle wrapper and then
runs the installed binary. Earlier rehearsals observed `gradle run` hanging after
application exit.

Optional Langfuse credentials in `.env` enable Koog traces in rounds 2–4. Round 4
includes typed CLI inputs/outputs, provider/role/subscription metadata, and the
application prompt supplied to the judge. CLI spans do not claim API token prices.
The runbook walks through the executed graph after the live run; Viktor uses his
own LC4J observability tooling.

See `RUNBOOK.md` for the stage sequence and rehearsal checks. One integrated smoke
run on 2026-09-08 took 64.1 seconds with a rejection, refinement, and approval; this
is an observation, not a stage timing guarantee.
