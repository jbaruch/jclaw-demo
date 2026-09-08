# Build brief: the LangChain4j Agentic side of j-claw

**Audience: a coding agent working for Viktor Gamov.** Read this file before writing
code. Ask Viktor when a decision is marked HIS CALL.

**Deadline: 2026-09-08, 15:00 CEST.** The two implementations run side by side in
*Codepocalypse Now: LangChain4j vs JetBrains Koog*.

## What you are building

The counterpart to the Koog implementation of **j-claw**, a general-purpose
personal assistant. The conference demonstrates it using one task: getting Baruch
out of *Basic AI Proficiency Training (Mandatory)* run by *Dana from People Ops*.
The four rounds reveal missing tools, then missing memory, add memory and skills,
then introduce typed handoffs and a reviewer that can block sending.

Keep the assistant's identity, welcome, and reusable skills independent of that
task. Calendar facts come from the mock tools, prior messages from memory, and
demo-specific background from the selected decline workflow. A greeting must not
claim it has inspected a calendar. Ordinary questions and text rewrites must work
without assuming a meeting, Dana, or a previous critic verdict.

In round 4 the roles are fixed: **Gemini identifies, Claude subscription drafts and
refines, Codex subscription judges**. Claude has no tools and creates no calendar
alibi. It produces a proposed message and hallway script. The application asks the
human to confirm only after Codex approves the latest plan.

Reference: this repository's `app`, `domain`, `mocks`, and `tui` modules on branches
`round1` through `round4`. Read `README.md`, `RUNBOOK.md`, `Strategy.kt`, and
`CliCritic.kt`. The three-provider pipeline replaces earlier model arrangements;
old dry runs and timings do not validate the new one.

The frameworks may express the work differently. Match the visible capabilities,
model roles, and send gate while making the implementation idiomatic to LC4J.

## Shared contract

### 1. Domain types

Use the same field names, enum constants, and JSON semantics. Framework annotations
will differ.

```text
enum ExcuseFlavor {
  CALENDAR_CONFLICT,    // already used on Dana
  FAMILY_OBLIGATION,   // already used
  CUSTOMER_ESCALATION, // already used
  DEADLINE,
  ALREADY_PROFICIENT,
  EXISTENTIAL_CRISIS,
}
enum PlausibilityTier { AIRTIGHT, CREDIBLE, THIN, HR_WILL_NOTICE }

DeclineRequest    { eventId, recentlyUsedFlavors[], knownAttendees[], organizerName }
DeclineDeployment { flavor, fakeCalendarEventId, messageToOrganizer, hallwayScript }
DeclineCritique   { tier, approved, feedback }
```

Include descriptions for every enum constant and field. Read the descriptions in
`domain/src/main/kotlin/jclaw/domain/Model.kt`; the model needs the vocabulary's
meaning. `fakeCalendarEventId` is nullable and must be null for round 4 because the
drafting stages have no calendar-write capability. A draft claiming a created
event is invalid.

### 2. The MCP servers

Use this repository's mock MCP jars so both sides see the same scenario. From the
repository root:

```bash
./gradlew -q :mocks:mcpJars
```

Outputs are `mocks/build/libs/calendar-mcp.jar` and
`mocks/build/libs/organizer-mcp.jar`. Launch them as child processes and use stdio
MCP. Both servers log calls to stderr; route these into the visible trace pane.

| Server | Tools |
|---|---|
| `calendar-mcp` | `getCalendar()`, `createCalendarEvent(title, startIso, durationMin)` |
| `organizer-mcp` | `getOrganizerSensitivity(name)`, `sendDecline(eventId, message)` |

`getCalendar` reports three prior declines but never the reasons. That missing
history is the reason for the memory round. `Dana from People Ops` has sensitivity
`TOUCHY`; everyone else is `NORMAL`.

### 3. Four branches, each interactive

Koog uses one `app` module and one source path,
`app/src/main/kotlin/jclaw/Main.kt`. Branch switching changes the implementation
under the open editor tab. The launcher performs the switch:

```bash
./jclaw 3
```

Warm every branch's installed application and mock jars before the stream, as in
`RUNBOOK.md`. Run from the repository root with the Gradle wrapper for builds and
`./jclaw` for interactive demos. There are no separate `:round1-chatbot:run` modules.
Keep stage changes committed before switching rounds.

`./jclaw N` copies the same opening request to the clipboard and resets generated
rehearsal memory to the three committed stories. Paste the request into PROMPT.
A bare `./jclaw` reruns the current branch while retaining its memory.

| Round | Interaction | Capability or limitation to inspect |
|---|---|---|
| 1 | Ask to escape the training, then insist: “Send Dana an email declining the Basic AI Proficiency Training on Tuesday.” | It can draft words but has no tools to send the email; this round demonstrates missing tools |
| 2 | Same opening ask with mock MCP tools; compare calendar results with its claimed prior excuses | It can act, but may mistake previous calendar events for excuses it actually used; no memory supplies those reasons |
| 3 | Same ask with prior declines retrieved from disk, then “rewrite in corporate-speak” | Disk memory supplies past sends; conversation memory supplies the current draft; the discovered skill rewrites it at default intensity 11 |
| 4 | Same ask, then follow-ups | Three models exchange typed data; Codex can reject, Claude can refine, and the application gates sending |

Let the actual output determine the commentary. A specific lie, repeated excuse,
or new category is not guaranteed. Every round remains a chat loop so the
limitation can be explored through follow-ups.

Round 4 classifies each input as an excuse request or ordinary chat. Ordinary chat,
including skill-based rewrites, returns `ChatReply` and never asks for send
confirmation. Rewriting a message is not a request to run the decline workflow.

### Stage 3: memory and skills — same narrative placement on both sides

Introduce the skill **inside stage 3, immediately after the memory demonstration**,
before the Tessl bridge and stage 4. The combined stage has eight minutes: roughly
five for memory and three for skills. Skills are a core stage-3 capability and
remain available in stage 4; they are no longer a standalone flourish after the
critic's verdict. The skills slide is now **11**; the stage-4 pipeline is **14**,
and the optional typed CLI adapter is **15**. Eval slides 16 and 17 stay in place.

Memory reads the three committed stories from `memory/documents/`. Seeded and new
documents all use UUID filenames; the readable story is inside the file. Persist
the literal message from a successful `sendDecline` call, not a model summary or
an unsent draft. A new process can retrieve it. Rewrites alone create no sent-message
memory. Documents are the source; embeddings are a rebuildable cache.

**Conversation memory is also required from stage 3 onward.** Keep the actual user
and assistant turns within the current session so “rewrite in corporate-speak”
can refer to the immediately preceding draft without repeating it. Koog uses
`ChatMemory`; use the idiomatic LC4J conversation-memory equivalent. This is
separate from long-term retrieval of past sends. A restart starts a new chat but
preserves confirmed sends on disk. Do not re-ingest earlier delivery receipts
when old chat turns are replayed. Carry conversation memory into stage 4, including
the actual plan/verdict shown to the user; its follow-up rewrites remain chat and
cannot silently replace an approved outbound plan.

Use the same generic skill file, `skills/corporate-speak/SKILL.md`. Its capability
is **corporate register at an intensity from 1 to 11** for any supplied update,
request, announcement, apology, or other message. At 11 this is ridiculous comedy:
pile on synergy, low-hanging fruit, boiling the ocean, moving the needle, circling
back, and the rest of the terrible corporate clichés. It must be funny, not merely
formal. Facts, numbers, intent, responsibility, and commitments
stay intact. Do not invent enthusiasm, approvals, reasons, or promises. The skill
contains no assumed recipient, training refusal, user biography, or approved draft.
Default to intensity **11** when the user does not specify a level. Use the draft
already in the conversation for follow-up rewrites. Ask for source text only if
neither the request nor the conversation supplies it.

**Implementation parity for Vik's agent:** discover skill folders at startup from
an explicit absolute directory; put only their names/descriptions and locations in
the agent's catalog; let the model select the relevant skill and read its body
through read-only file tools before applying it. Show the directory listing and
`SKILL.md` read in the trace. New or edited skill files are picked up on the next
run without recompiling. Koog uses `discoverSkills`, `generateSkillsPrompt`,
`ListDirectoryTool`, and `ReadFileTool`; use idiomatic LC4J equivalents. The skill
belongs to ordinary interactive chat, not only a special command or a hardcoded
rewrite step after judging. Keep the loaded skill out of unrelated task prompts.

**After the stage-3 answer, type this in the same chat:**

> rewrite in corporate-speak

**Optional follow-up:**

> tone it down to 4

**See this:** the skill catalog leads to a visible file read; conversation memory
supplies the draft without a paste; intensity 11 fills it with ridiculous clichés.
The optional follow-up reduces the jargon around the same message. No calendar or
organizer action is needed for a rewrite. Show the restart/persistent-memory beat
after these follow-ups, not between the draft and its rewrite. The skill stays
generic even though this demo applies it to the meeting draft.

**Highlight in code:** the skill's frontmatter and intensity/preservation rules,
then discovery/catalog/read-tool wiring and both memory installations in stage 3.
The instructions live in a file the agent chooses to read. Do not paste its body
or the example output into the generic system prompt. If late, shorten the second
rewrite; retain the skill introduction on both sides.

### 4. The pipeline and the veto

```text
start → classify → chatReply → ChatReply
           ↓
        identify (Gemini) → deploy (Claude) → verify (Codex)
                                                ├─ approved → ReadyToSend
                                                ├─ rejected → refine (Claude) → verify
                                                └─ exhausted or invalid verdict → Blocked

ReadyToSend → application human confirmation → sendDecline
```

The application result has three cases: `ReadyToSend`, `Blocked`, and `ChatReply`.
Only `ReadyToSend` reaches the human-confirmation path. There is no `approve` node
inside the current Koog graph and no human override of a rejected plan.

| Phase | Input → output | Provider | Capabilities |
|---|---|---|---|
| `classify` | `String` → `ClassifiedInput` | Gemini API | none |
| `identify` | `String` → `DeclineRequest` | Gemini API | read tools and normal-mode memory retrieval |
| `deploy` | `DeclineRequest` → `DeclineDeployment` | Claude subscription | no CLI or MCP tools |
| `verify` | latest plan → `DeclineCritique` | Codex subscription | supplied plan and context only; no tools |
| `refine` | latest plan and feedback → `DeclineDeployment` | Claude subscription | no CLI or MCP tools |
| `chatReply` | `String` → `String` | Gemini API | read tools and discovered skills |

Make the review visible in the terminal: print Claude's initial message and hallway
script before the first Codex review, then each revised draft before its review.
Label drafts and verdicts with matching attempts. The audience must see what was
rejected; printing only critiques and the final approved plan loses the story.
Keep this ordered transcript in conversation memory for follow-up questions too.

Koog carries each draft in a `ReviewAttempt` with a request-local refinement count.
A rejection allows **two refinements**. Codex reviews each revision; rejection after
the second refinement blocks the result. This means at most three verdicts per
request, not two refusals followed by an override. A missing, malformed, failed, or
timed-out verdict blocks immediately. A new request starts with its own allowance.

Sending happens only after explicit critic approval and human confirmation. Show
the latest approved message to the human; send that same message once. The TUI
accepts `send`, and the stdout fallback accepts `y` or `yes`. Record memory after a
successful send. If memory persistence then fails, report the persistence error
without claiming the message was never sent.

## Models and typed CLI handoffs

Rounds 1–3 use the configured Gemini Flash profile through `GOOGLE_API_KEY`.
The current default in `Models.kt` is `gemini-3.7-flash`; `JCLAW_FLASH` selects the
other configured profiles. Round 4 retains Gemini for context gathering and chat,
then uses the locally authenticated Claude and Codex subscriptions for their roles
above. CLI model versions follow the installed CLI configuration/defaults; do not
label an unverified version on stage.

Koog supplies a typed convenience factory for Claude CLI. Its Codex convenience
factory lacks that typed handoff. **Codex CLI itself supports `--output-schema`.**
`TypedCodex.kt` implements the integration by generating JSON Schema from a Kotlin
serializer, passing it to `codex exec`, and validating and decoding the final
successful response into `DeclineCritique`. Failed turns, invalid fields, wrong
JSON types, and malformed final answers cannot become approval.

This adapter is the optional code-walkthrough beat after the core demo. All three
providers already run in the main pipeline. There is no alternate critic switch
and no separate cross-vendor rerun required. LC4J should provide the same typed
contract through its own integration; the adapter code need not look the same.

One integrated smoke run on 2026-09-08 took 65.0 seconds and exercised all three
providers, rejection, refinement, approval, and human refusal without a send. This
is one observation. Time the normal request, refinement path, and context comparison
during rehearsal; historical all-Gemini timings do not apply to this pipeline.

## Keep CLI stages confined to the supplied text

The CLI runs on the speaker's machine. Give it a temporary workspace and disable
its tools and inherited integrations; a workspace alone does not disable MCP.
The demo's external actions belong to the mock servers under application control.

The current Claude configuration in `CliCritic.kt` uses `--safe-mode`,
`--strict-mcp-config`, the single argument `--tools=`, and no session persistence.
It selects Claude subscription login through `forceLoginMethod = claudeai`.
Use the exact flags supported by the installed CLI; do not turn the empty tools
argument into a variadic argument that can consume the prompt.

`TypedCodex.kt` ignores user config and rules, disables CLI tool providers and host
skill discovery, uses an ephemeral read-only workspace, disables web search, and
selects ChatGPT subscription login. Consult that file for the complete installed-CLI
flag set.

`apiKey = null` alone does not remove an inherited API credential.
`SubscriptionCliTransport` removes API billing keys and alternate-provider settings
from child processes while preserving the local subscription login.

The capability boundary for round 4 is simple: Gemini can read the mock scenario,
Claude and Codex only process supplied text, and the application owns the mock send.
Neither the draft nor the judge has a tool that can skip the application's gate.

## The context comparison

The Koog flag `JCLAW_NAIVE=1` removes memory retrieval and the extra context from the
identification step. The typed graph and all three providers stay in place, and
Claude and Codex still receive `Scenario.USER_CONTEXT` and the other scenario facts.
This compares identification context; it does not isolate typing.

After the normal round-4 run, quit the TUI and run:

```bash
JCLAW_NAIVE=1 ./jclaw
```

Ask the chat to predict the difference, then read the actual result. Do not promise
that the naive run must repeat a burned excuse or that a refinement must succeed.

## Observability — core stage-4 walkthrough

Reserve about three minutes inside stage 4, after the core run and before the
context comparison. Both speakers explain the actual route through the multi-model
workflow. **Koog uses Langfuse; Viktor uses his existing LangChain4j observability
setup. LC4J does not need Langfuse or its credentials.** Show comparable step
inputs, outputs, verdicts, and durations in each side's own tooling. The Langfuse
instructions below are for the Koog side only.

`LANGFUSE_BASE_URL`, `LANGFUSE_PUBLIC_KEY`, and `LANGFUSE_SECRET_KEY` enable Koog's
OpenTelemetry export. Finish or hold the send, then quit the TUI cleanly so the
remaining spans close and the exporter flushes. Open
[the project traces](https://us.cloud.langfuse.com/project/cmts07jea03ryad0d7jajuw1m/traces),
refresh, and select the newest `jclaw-round4` with `critic:codex` and
`drafter:claude-code`. Verify the timestamp/session. The
[verified 2026-09-08 rehearsal](https://us.cloud.langfuse.com/project/cmts07jea03ryad0d7jajuw1m/traces/2c077ed67b9345f2173997f01225605a)
contains rejection → refinement → approval; label it as a rehearsal if used.

If hidden, use the trace toolbar's **View Options → Show Graph**, then open the
**Graph** bar below the trace tree. Select **Expanded** at the graph's top left
to follow each actual call, including
separate verify attempts. **Aggregated** gives the compact shape and repeat counts.
Inspect deploy's typed input and draft, verify's complete critique/feedback, then
refine's revised draft and the next verdict when present. The judge's actual
application-supplied prompt is in verify metadata; its question is about the best
available plan, not truthfulness. Follow the result actually recorded.

For projection, collapse the detail panel and sidebars and shrink the tree to its
minimum height; the graph then uses the full trace width and 80% of the navigation
pane. Langfuse remembers these pane sizes and Expanded mode in this browser.
Agent graphs cannot be saved as Langfuse dashboard widgets or named layout presets.
Pin/bookmark the rehearsal trace for quick access; its current browser layout is
remembered, while exact graph pan/zoom is temporary. These are Koog-side UI notes;
Viktor should use the equivalent view in his own LC4J observability tooling.

The native Koog stage spans retain their hierarchy and graph metadata and expose
typed inputs/outputs. Claude/Codex spans identify provider, client, subscription
authentication, and role. Gemini generations expose their available model/usage
details. CLI calls are not fabricated API generation or billing records; do not
claim token/cost coverage for them. Human confirmation, the application-owned mock
send, and memory persistence happen outside this graph.

`./jclaw graph` emits `pipeline.mmd` from the live strategy through
`asMermaidDiagram()`. It shows possible routes and remains an optional code visual;
the Langfuse walkthrough shows the route that ran.

The shared TUI is available on all four branches. Its header shows `j-claw` and
feature badges, without a repeated stage name; stages 3 and 4 include `SKILLS`.
Render model Markdown with normal-weight body text and actual emphasis, not visible
markup or an entirely bold reply. Round 4 retains the FLOW row and updates each
phase's stopwatch in place, for example
`deploy · Claude (subscription) · STARTED (running 20s)`.
Completed, failed, or cancelled phases freeze their duration. A repeated verify
call gets its own timer; no growing row-per-second log.

## The ending

The supplied context says Baruch builds AI agents for a living and is presenting a
conference talk about building them that afternoon. Codex is asked whether the
candidate is the **best available excuse and plan for his situation**. Do not ask
whether the story is true, instruct the judge to prefer honesty, or require a moral
rejection. The intended payoff is that it finds the real reason more useful on its
own.

If Codex rejects fabrication and proposes that real reason, read its reasoning,
then show Claude's revision. If Claude already drafted the honest reason or Codex
chooses another objection, follow the actual output. If Codex approves immediately,
say so. Never substitute a scripted rejection for the live verdict. A blocked send
is also a valid demonstration of the critic's power.

## Your other job: the deck

Viktor owns the deck for this delivery. The full slide plan is generated and lives in
the talk directory, not here:

```
<presentations>/IdeaConf/2026/Codepocalypse/slides.md    per-slide build sheet
<presentations>/IdeaConf/2026/Codepocalypse/script.md    per-slide spoken script
<presentations>/IdeaConf/2026/Codepocalypse/outline.yaml source of truth
```

**20 slides**, Neo-Tokyo arcade identity carried over from JNation. Five things that
are not optional:

- **Title must read** *"Codepocalypse Now: LangChain4j vs JetBrains Koog"* — that is
  the scheduled title, and LangChain4j is named first.
- **Footer:** `@jbaruch  @gamussa  #ideaconf  #codepocalypse  speaking.jbaru.ch`
- **QR:** `ideaconf-2026-codepocalypse-qr.png` in the talk directory, already generated
  (encodes `jbaru.ch/ideaconf-2026-codepocalypse`). Do not generate a new one.
- **Say the verdict lines out loud.** At JNation four thesis slides were never spoken
  aloud. `script.md` marks them `SAY IT`.
- **Slides 16 and 17 are the eval beat and they are a pair.** 16 is a two-row high-score
  board (MAY 25%→100%, 4× on top, greyed; SEPT 44%→77%, 1.75× below, bright) — the
  argument is that the lift *shrank*. 17 replaces the score bars with token meters:
  `BASELINE: 2.06× THE TOKENS. FOR THE WORSE ANSWER.` Both carry a source caption.
  This is the most protected beat in the talk; do not merge them into one slide.

There is no source `.pptx` — the JNation deck exists only as a PDF.

Note the deck now has 20 slides, not the 18 an earlier draft of this file said.

## Verification before delivery

1. Build all four branches and rehearse them in their target terminal, in order.
2. Keep the opening request identical and read the actual round-2 tool calls and
   round-3 memory retrieval before describing the result.
3. Verify the core round-4 trace shows Gemini identification, Claude drafting and
   refinement when needed, and Codex review through the intended subscriptions.
4. Exercise rejection through both refinements, invalid/unavailable verdicts, and
   a new independent request. Rejected results must never offer a send override.
5. Verify human refusal sends nothing and human approval sends only the exact
   latest critic-approved plan once. Chat replies do not request confirmation.
6. Inspect the CLI restrictions and ensure these stages cannot access real tools.
7. Time the new pipeline and agree the optional cuts; do not reuse timings from
   the retired all-Gemini or alternate-critic builds.
8. Check the trace and TUI badges at streaming font size. Retain the FLOW row while
   omitting redundant round/stage titles in the header. Confirm Markdown rendering
   and that phase stopwatches advance during long calls without keyboard input.
9. In stage 3, demonstrate corporate-speak through ordinary chat after memory.
   Check that the bare follow-up uses the previous draft, with visible skill read,
   default intensity 11, and optional “tone it down to 4”. Verify fact preservation,
   no send or sent-message memory from a rewrite, and no duplicate old receipts.
   Keep this capability in stage 4 and introduce it at the same place in both talks.
10. Open the completed run in each framework's observability tool and walk its
    actual path, typed handoffs, and verdicts. Keep demo facts out of the generic
    welcome, persona, and skill.

## Open questions — Viktor decides

1. Which LC4J Agentic constructs express classification, typed handoffs, the bounded
   review loop, and the application send gate most clearly? The choice is HIS CALL.
2. Which LC4J memory implementation reads the same prior declines from
   `memory/documents/`? Use those files as the seed.
3. Should a `VIKTOR_CLASSIC` flavor be added? Agree a shared enum before diverging
   from the JSON contract.
4. How will LC4J integrate the fixed Gemini/Claude/Codex roles with subscription
   authentication and typed output? Keep the visible roles and veto semantics.
5. Use the shared `:tui` module or match its layout? Both sides should show comparable
   chat, trace, and prompt panes, with the app name and feature badges in the header.
6. Use the existing LangChain4j observability setup for Viktor's walkthrough;
   choose its clearest view of the executed workflow and typed handoffs.
