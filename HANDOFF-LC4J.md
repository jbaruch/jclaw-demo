# Build brief: the LangChain4j Agentic side of j-claw

**Audience: a coding agent working for Viktor Gamov.** Read this file before writing
code. Ask Viktor when a decision is marked HIS CALL.

**Deadline: 2026-09-08, 15:00 CEST.** The two implementations run side by side in
*Codepocalypse Now: LangChain4j vs JetBrains Koog*.

## What you are building

The counterpart to the Koog implementation of **j-claw**, which gets Baruch out of
*Basic AI Proficiency Training (Mandatory)* run by *Dana from People Ops*. The
four rounds reveal missing tools, then missing memory, then the need for typed
handoffs and a reviewer that can block sending.

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
| 1 | Ask to escape the training, then ask it to revise and quote its previous excuse | No tools, and no memory of the previous turn; compare claimed actions against those limits |
| 2 | Same ask with mock MCP tools | It can act, but calendar history cannot tell it which excuse it used before |
| 3 | Same ask with the three prior declines retrieved from disk | It can avoid earlier excuses; inspect whether its chosen vocabulary matches the domain model |
| 4 | Same ask, then follow-ups | Three models exchange typed data; Codex can reject, Claude can refine, and the application gates sending |

Let the actual output determine the commentary. A specific lie, repeated excuse,
or new category is not guaranteed. Every round remains a chat loop so the
limitation can be explored through follow-ups.

Round 4 classifies each input as an excuse request or ordinary chat. Ordinary chat
returns `ChatReply` and never asks for send confirmation.

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
| `chatReply` | `String` → `String` | Gemini API | read tools |

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

## Optional observability on the Koog side

- `./jclaw graph` emits `pipeline.mmd` from the live strategy through
  `asMermaidDiagram()`. Human confirmation and sending occur in application code
  outside that graph.
- `LANGFUSE_BASE_URL`, `LANGFUSE_PUBLIC_KEY`, and `LANGFUSE_SECRET_KEY` enable the
  OpenTelemetry export. Inspect available phase/model details and token/cost data;
  subscription CLI calls may have less detail than API calls. Round 4 labels the
  drafter `claude-code` and critic `codex`.

Neither is required for LC4J parity. The shared TUI is available on all four
branches. Its header shows `j-claw` and feature badges, without a repeated stage
name. Round 4 also shows the live FLOW row.

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
   omitting redundant round/stage titles in the header.

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
6. If exporting to the same Langfuse project, use the shared credentials and tag the
   LC4J side `langchain4j` so traces can be distinguished.
