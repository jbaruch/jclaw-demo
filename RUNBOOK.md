# j-claw — stage runbook

IntelliJ IDEA Conf 2026 · Day 1, 15:00–16:00 CEST · Baruch (Koog) + Viktor (LangChain4j Agentic)

## Preparation

Run every command from this repository's root. The rounds are branches
(`round1` through `round4`) sharing one `app` module. Commit stage changes before
switching branches. Use `./jclaw N` off-camera while the other speaker is presenting.

Configure `.env` from `.env.example` before sharing the terminal. It supplies
`GOOGLE_API_KEY` and optional Langfuse credentials. Round 4 also requires `claude`
and `codex` on `PATH`, logged into their subscriptions. Check subscription login
before the stream; no API key is needed for either CLI stage.

Warm every branch's build and, from round 2 onward, both MCP jars before the stream:

```bash
for round_branch in round1 round2 round3 round4; do
  git switch "$round_branch" || exit
  ./gradlew -q :app:installDist || exit
  if [ "$round_branch" != round1 ]; then
    ./gradlew -q :mocks:mcpJars || exit
  fi
done
git switch round1
```

Use `./jclaw` to run the installed application. Earlier rehearsals observed Gradle's
interactive `run` task hanging after the application exited. The launcher builds
with the wrapper first; this also prevents a previous branch's installed binary
from being used after a switch.

Time the current build in the actual streaming terminal. Older all-Gemini round-4
measurements do not establish this pipeline's runtime. Allow for subscription CLI
startup, model calls, and up to two refinements. Each CLI call has a three-minute
timeout; that is a failure bound, not a promise about total runtime.

Validation on 2026-09-08: the full build and 21 deterministic tests passed (7 review/
send-gate, 12 typed-adapter, and 2 telemetry tests). Independent terminal fixtures
verified live stopwatch redraw without keyboard input, frozen final durations,
separate retries, and shutdown cleanup. A fresh smoke run took **64.1 seconds**
wall time: Codex rejected, Claude refined, and Codex approved `ALREADY_PROFICIENT`.
Human `n` held the plan and no send call occurred. The exported trace contains the
typed handoffs, both full verdicts, provider metadata, and the supplied judge prompt.
This is one smoke observation, not a stage timing guarantee or a promised response.

## The four rounds

`./jclaw N` switches branches, starts the three-pane TUI, and copies the same opening
ask to the clipboard. Paste it into PROMPT:

> Get me out of the Basic AI Proficiency Training on Tuesday, run by Dana from People Ops. Don't reuse an excuse I've already used on her - tell me which ones you're avoiding.

Each round is interactive. Follow-ups go into PROMPT; use Ctrl-C to leave the TUI.
The stdout fallback (`./jclaw plain`) exits on a blank line or Ctrl-D.

j-claw's identity is a personal assistant; this training request is a user-supplied
example. Calendar events, organizer sensitivity, and the three seeded stories are
mock scenario data.

| Round | Command | What to demonstrate |
|---|---|---|
| 1 | `./jclaw 1` | Paste the opening ask, read the draft, then type **“Send Dana an email declining the Basic AI Proficiency Training on Tuesday.”** It has no tools to send it. Show the factory without a tool registry. |
| 2 | `./jclaw 2` | Same ask. It can now act. Compare the calendar events with its claimed "excuses avoided": highlight any calendar events treated as previous excuses without evidence. Follow the live answer. |
| 3 | `./jclaw 3` | Show retrieved prior excuses and the resulting draft. Follow with “rewrite in corporate-speak”: conversation memory supplies that draft, the skill turns it up to 11. After the follow-ups, a bare restart demonstrates persistent memory of actual sends. |
| 4 | `./jclaw 4` | Gemini identifies, Claude subscription drafts, Codex subscription judges, and Claude refines if rejected. Watch the phase stopwatches, then explain the completed run in Langfuse. Only approval reaches the application's send confirmation. |

Read actual output. A particular fabricated meeting, repeated excuse, new category,
or critic objection is not guaranteed. If a model succeeds sooner, say so.

`./jclaw N` starts with the three committed prior declines and clears generated
rehearsal memory. To show round 3 remembering its own previous send, exit and run
bare `./jclaw`: that preserves memory across processes. Do not use `./jclaw 3` for
that continuity beat, because it resets the rehearsal.

## Round 3 — memory and Agent Skills

Read the three prior messages from `memory/documents/`, then compare retrieval with
the answer. After a successful mock send, inspect the newly recorded file: its
UUID filename is an identifier, and its contents are the actual message sent.
An unsent draft, failed send, or standalone rewrite must not be recorded as sent
history. `ChatMemory` separately retains the current session's conversation,
including the draft just shown. `LongTermMemory` supplies confirmed send history
from disk. Keeping old turns in chat must not ingest their delivery receipts again.

Introduce Agent Skills with a natural follow-up to the draft already on screen:

> rewrite in corporate-speak

Show the agent discovering and reading `skills/corporate-speak/SKILL.md`; the native
file tools appear as `__list_directory__` and `__read_file__` in TRACE. Open the
skill file beside the result. Its intensity range is 1–11 and defaults to 11.
It applies to any supplied or preceding draft. Read the ridiculous clichés while
checking that the underlying message remains intact. No pasted source is needed.

For an optional second follow-up:

> tone it down to 4

Cut this second rewrite if short on time; keep the natural follow-up and the
level-11 joke. Conversation memory and skills remain in round 4's ordinary chat;
rewriting does not request send confirmation or alter a critic-approved plan.

**After the skill follow-ups**, exit and run bare `./jclaw` to show recall of actual
sends in a fresh process. That restart clears the current chat, including unsent
drafts; only recorded sends persist. Ask the full opening question again to get a
new draft. Do not restart between the draft and “rewrite in corporate-speak”.

The standalone runner is a rehearsal convenience in rounds 3–4. Quit the TUI first:

```bash
./jclaw skills 4 'The release is delayed because tests are failing. I will send an update tomorrow.'
```

Supply the message as an argument, through `JCLAW_MESSAGE`, or on standard input.
There is no built-in sample. This runner has read-only skill tools and no send tool
or memory ingestion. Label any supplied sample as a sample when demonstrating it.

## Round 4 — the core three-provider demo

Leave the typed contracts visible beside the terminal. The FLOW row shows
`identify → deploy → verify ⇄ refine`. Each phase has a TRACE stopwatch, such as
`deploy · Claude (subscription) · STARTED (running 20s)`, updated in place each
second. Completion/failure freezes the elapsed duration; each retry gets a new row.

1. Point at Gemini gathering the obligation, attendees, and prior excuse flavors.
2. Show Claude taking a `DeclineRequest` and returning a `DeclineDeployment` through
   the subscription CLI. Read the initial draft and hallway script printed in CHAT
   before Codex starts reviewing it. Claude has no tools;
   no supporting calendar event is created, and `fakeCalendarEventId` must be null.
3. Show Codex reviewing the draft through its subscription CLI. Its question is
   whether this is the best available excuse and plan for Baruch's situation.
4. Read the verdict. If Codex objects to fabrication and proposes the real reason,
   land the story: the review found honesty useful without a truthfulness question
   or an instruction to prefer a moral answer. If it raises another objection,
   follow that one. If it approves the first draft, report that.
5. On rejection, read the revised draft printed before Codex's next review. Each
   draft and verdict is labelled with its attempt so the audience can match them.
   After two refinements, another rejection produces `Blocked`; nothing can be sent.
6. After approval, read the exact latest plan. The TUI asks for `send`; any other
   reply holds it. The stdout fallback asks for `y` or `yes`. Sending is owned by
   the application after the graph returns `ReadyToSend`.
7. Quit cleanly and show the completed run in Langfuse (walkthrough below).
   Reserve three minutes for this inside stage 4's existing budget, before the
   context comparison. The observability explanation is part of the core demo.

A missing, malformed, failed, or timed-out Codex verdict blocks immediately. A
human cannot override it. `JCLAW_AUTOSEND=1 ./jclaw plain` automatically confirms
an approved plan for mock rehearsal only; it does not bypass the critic and is not
used by the TUI. Keep it unset when demonstrating human confirmation.

Once a mock send succeeds, the application records the message in memory. A
persistence error after delivery must be reported separately from a blocked send.
If a send was attempted but its outcome is unknown, inspect the receipt/log before
retrying.

## Round 4 — context comparison

Run the normal round-4 request and its Langfuse walkthrough first. Keep the same branch:

```bash
JCLAW_NAIVE=1 ./jclaw
```

Ask the chat to predict what changes before running it. The flag removes memory
retrieval and the extra context from Gemini's identification step; typed handoffs,
the three providers, and the scenario facts supplied to Claude and Codex remain.
It does not isolate the effect of typing. Read the actual difference and do not
announce a planned failure. Quit and use bare `./jclaw` to return to normal mode.

## Optional adapter walkthrough — the cut line

All three providers already ran in the core demo. The optional beat is a code
walkthrough, with no second cross-vendor run required:

- `CliCritic.kt`: Claude's typed Koog factory and subscription configuration.
- `TypedCodex.kt`: Codex CLI already accepts `--output-schema`; the missing piece
  is Koog's typed Codex integration. Show schema generation from the Kotlin
  serializer, the CLI argument, and strict decoding of the final successful answer.
- `Review.kt` and `JclawResult.kt`: explicit approval produces `ReadyToSend`;
  rejected or unavailable verdicts cannot reach human confirmation or sending.

`./jclaw codex` is a standalone adapter probe for rehearsal. Skip the walkthrough
if the clock is tight; the main pipeline already established the three-model story.

## Optional strategy graph

After exiting the round-4 TUI:

```bash
./jclaw graph
```

Open `pipeline.mmd` in IntelliJ to render the graph generated from the live strategy.
The human confirmation and send are application code, so do not describe an
`approve` graph node.

## The TUI

All four rounds use CHAT, TRACE, PROMPT, and a busy status line. The header shows
`j-claw` plus one badge per feature. It has no redundant round
or stage title. MCP starts in round 2, MEMORY and SKILLS in round 3, and WORKFLOW
in round 4. Round 4's separate FLOW row remains; the active stage is yellow,
completed stages green, and failed stages red. Gemini subgraph events and CLI stage
callbacks drive it.

CHAT renders model Markdown with normal-weight paragraphs and styled headings,
emphasis, lists, links, and code. Long replies wrap and scroll through the same
chat pane.

Tool calls, mock-server logs, and memory activity appear in TRACE. Library stdout
and stderr go to `jclaw-tui.log`. Check every round at the actual streaming font
size during rehearsal. Use `./jclaw plain` if the TUI fails.

## Koog observability in Langfuse

Baruch uses Langfuse for the Koog walkthrough. Viktor uses his own LC4J
observability tooling; matching Langfuse export is not a requirement for his side.

Set `LANGFUSE_BASE_URL`, `LANGFUSE_PUBLIC_KEY`, and `LANGFUSE_SECRET_KEY` in `.env` to
enable traces in rounds 2–4. Without credentials the feature is not installed.
Each process is a session, and traces are named `jclaw-roundN`. Round 4 tags include
`domain-modelled` or `naive`, `drafter:claude-code`, and `critic:codex`.

After finishing or holding the send, quit cleanly so the agent's remaining spans
close and flush. Open [the project's traces](https://us.cloud.langfuse.com/project/cmts07jea03ryad0d7jajuw1m/traces),
refresh, and select the newest `jclaw-round4` with `critic:codex` and
`drafter:claude-code`. Match its timestamp/session to the run you just completed.
The [verified 2026-09-08 rehearsal trace](https://us.cloud.langfuse.com/project/cmts07jea03ryad0d7jajuw1m/traces/2c077ed67b9345f2173997f01225605a)
is available for preparation; identify it as a rehearsal if you use it on stage.

The three-minute walkthrough:

1. In the trace's left pane, open **View Options** (the sliders icon) and enable
   **Show Graph** if needed. Expand the **Graph** bar below the trace tree if it is
   collapsed. Use the graph's top-left **Aggregated / Expanded** toggle to choose
   **Expanded**. Point out deploy → verify and, if it happened, refine → verify.
   **Aggregated** gives the compact view with repeated-step counts. Do not narrate
   a loop that did not run.
2. Open **deploy**: show the typed request and Claude's draft output.
3. Open **verify**: show the candidate, the complete Codex verdict, and feedback.
   Its metadata contains provider/role/subscription details and the application
   prompt supplied to Codex, generated by the same helper used during execution.
4. Open **refine**, if present: compare the revised draft with that feedback,
   then the next verdict. Follow the trace to `readyToSend` or `blocked`.
5. Show each phase's **duration**. The terminal stopwatches make the wait visible;
   the trace lets you explain where the time went.

For projection, collapse the browser and Langfuse sidebars and resize the dividers
to give the graph most of the page. Collapse the detail panel when explaining the
graph; **Show detail panel** at the far right reopens it for typed inputs/outputs.
Use **Fit to view** (the corners icon) to fit the executed graph.
The tree's minimum height leaves the graph 80% of the navigation pane. To restore
this layout with the keyboard, focus the vertical divider and press **End** to
collapse details; focus the divider above Graph and press **Home** to minimize the
tree. Pane sizes, graph visibility, and Expanded mode save automatically in this
browser. Langfuse dashboards support metric charts, not this agent graph. Pin or
bookmark the trace for access; it reopens using the current saved layout, not a
named layout snapshot. Exact pan/zoom is temporary.

Code pointer: `install(OpenTelemetry) { langfuse(...) }` in `Tui.kt`, then the
export setup in `Observability.kt`. `./jclaw graph` remains a brief optional look
at the strategy's possible routes; Langfuse explains the executed run.

Gemini generations include prompts/completions and their reported usage. Claude
and Codex remain accurately represented as stage spans with typed input/output;
they are not API billing records. Embeddings, human confirmation, application-owned
delivery, and the subsequent memory write are outside the agent trace.

## If something fails

- **Missing MCP jar:** run `./gradlew -q :mocks:mcpJars` from the repository root and
  inspect the reported path. The launcher normally builds both jars.
- **Missing Google key:** set `GOOGLE_API_KEY` in `.env`; the launcher sources it
  before starting the installed binary.
- **CLI login or availability failure:** check the installed CLI and subscription
  login off-camera. A failed review must remain blocked; do not use an override.
- **Repeated rejection:** after two refinements the result is blocked. Show that
  the reviewer can stop the action; the last rejected draft is never sent.
- **Stage budget is tight:** cut the optional adapter walkthrough, context
  comparison, strategy graph, or optional “tone it down to 4” follow-up. Keep the
  stage-3 level-11 skill introduction and the core round-4 observability walkthrough.
  A recorded rehearsal may illustrate a result if identified as recorded.
- **TUI failure:** use `./jclaw plain`. An earlier round can illustrate earlier
  capabilities, but does not demonstrate round 4's critic veto.
