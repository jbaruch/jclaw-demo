# Build brief: the LangChain4j Agentic side of j-claw

**Audience: a coding agent working for Viktor Gamov.** Read this whole file before
writing code. Ask Viktor when a decision is marked HIS CALL; do not invent an answer.

**Deadline: 2026-09-08, 15:00 CEST.** Live-streamed, so a broken build is on YouTube
permanently.

## What you are building

The counterpart to a working Koog implementation. Both apps are the same agent —
**j-claw**, which gets the user out of *"Basic AI Proficiency Training (Mandatory)"*
run by *Dana from People Ops*. It does not merely decline: it stages a calendar event
to back the story up, calibrates to how much scrutiny the organiser applies, and
writes a hallway script for the next day.

The talk is a bake-off. The two apps run **side by side at the same wall-clock beat**.
Your job is parity of behaviour, not parity of code — the frameworks are supposed to
look different. What must match is what the audience sees happen.

Reference implementation: this repository (`jclaw-*` modules). It is committed,
runs, and has been dry-run six times. Read `README.md`, then `RUNBOOK.md`.

## Non-negotiable contract

Four things must be identical across both sides or the side-by-side stops reading.

### 1. Domain types

Same field names, same enum constants, same semantics. Your framework's annotations
will differ; the JSON shape must not.

```
enum ExcuseFlavor {
  CALENDAR_CONFLICT,    // burned - already used on Dana
  FAMILY_OBLIGATION,    // burned
  CUSTOMER_ESCALATION,  // burned
  DEADLINE,
  ALREADY_PROFICIENT,   // the payoff - see "The ending" below
  EXISTENTIAL_CRISIS,
}
enum PlausibilityTier { AIRTIGHT, CREDIBLE, THIN, HR_WILL_NOTICE }

DeclineRequest    { eventId, recentlyUsedFlavors[], knownAttendees[], organizerName }
DeclineDeployment { flavor, fakeCalendarEventId, messageToOrganizer, hallwayScript }
DeclineCritique   { tier, approved, feedback }
```

**Every enum constant needs a description annotation.** Without them the model picks a
constant that contradicts its own prose. We shipped `flavor: EXISTENTIAL_CRISIS`
attached to a message about a root canal before adding them.

### 2. The MCP servers — do not reimplement

Use the jars from this repo. Reimplementing them guarantees drift, and drift breaks
the comparison silently.

```
gradle :mocks:mcpJars
  -> mocks/build/libs/calendar-mcp.jar
  -> mocks/build/libs/organizer-mcp.jar
```

Real stdio MCP servers, official Kotlin SDK 0.11.1, protocol `2025-06-18`. Launch each
as a child process and speak MCP over its stdin/stdout.

| Server | Tools |
|---|---|
| `calendar-mcp` | `getCalendar()`, `createCalendarEvent(title, startIso, durationMin)` |
| `organizer-mcp` | `getOrganizerSensitivity(name)`, `sendDecline(eventId, message)` |

`getCalendar` reports that the user declined three prior sessions but **never why**.
That gap is deliberate and load-bearing — do not "fix" it. A calendar records that you
bailed; it does not record the story you told, and that is the entire justification for
the memory round.

`Dana from People Ops` → `TOUCHY`. Everyone else → `NORMAL`.

Both servers log every call to **stderr**. Keep that visible on stage.

### 3. Four rounds, four BRANCHES — and ALL FOUR ARE INTERACTIVE

**The Koog side is now branches, not modules.** One `app` module, one file at
`app/src/main/kotlin/jclaw/Main.kt`, and branches `round1`..`round4` that change its
contents. Baruch checks out off-camera while you are presenting, so the editor tab
stays open and the code appears to evolve rather than being four prepared copies.

```
git checkout round3 && ./jclaw
```

**Warm every branch before the stream.** A cold first build after a checkout took over
6 minutes; warm it is seconds. And never `gradle run` — see the note further down.

#### Every round is a chat loop. This is the spine of the talk.

This is the part an earlier version of this document got wrong, so it is spelled out.

Each round is a **REPL**, not a one-shot task. The audience watches us *talk* to the
thing, hit a wall, and then fix that exact wall in the next round. The limitation has
to be **discovered in conversation**, not asserted by us over a script:

| Round | What we chat about | The wall the audience sees |
|---|---|---|
| 1 | Ask it to get you out of the training | It says it *did* things it cannot do — claims a calendar event it has no tools to create. Ask what you just said: it has no memory. |
| 2 | Same ask, now it really acts | It acts, but reuses an excuse already used on Dana — the calendar records that you bailed, not what you told her |
| 3 | Same ask again | Reuse stops. But it invents an excuse category that is not in our domain, and nothing checks it |
| 4 | Same ask, and follow-ups | Typed subtasks, a critic, and a human gate |

Rounds 1-3 each end by exposing exactly what the next round installs. **If your side
runs a single scripted task and prints a result, the ladder collapses** — the audience
is told there is a problem instead of watching one happen.

Round 4 additionally routes each turn: a `classify` subtask decides whether the message
is a request to get out of something or just conversation, so the agent survives
follow-up questions instead of exiting after one answer.

### 4. The pipeline shape (round 4)

```
                 +--> chatReply -----------------> ChatReply
                 |
start --> classify
                 |
                 +--> identify --> deploy --> verify --(approved)--> approve --> ExcuseSent
                                                 ^         |
                                                 +- refine +  (rejected, with feedback)
```

`classify` routes each prompt to either the pipeline or a chat reply, so the agent
survives follow-ups instead of exiting after one excuse. Both branches converge on a
sealed `JclawResult` (`ExcuseSent` | `ChatReply`).

| Phase | In → Out | Tools available |
|---|---|---|
| `classify` | `String` → `ClassifiedInput` | none |
| `identify` | `String` → `DeclineRequest` | read |
| `deploy` | `DeclineRequest` → `DeclineDeployment` | read + write, **no user, no comms** |
| `verify` | `DeclineDeployment` → critique | read + **user** |
| `refine` | feedback → `DeclineDeployment` | read + write |
| `chatReply` | `String` → `String` | read |

## Which model runs which step, and why

Rounds 1-3 run entirely on **`gemini-3.7-flash`** via the Google API key. Round 4 mixes
subscriptions with that key.

| Step | Model | Why this one |
|---|---|---|
| rounds 1-3, all | `gemini-3.7-flash` (API key) | Rounds 2 and 3 demonstrate **Koog's** tool registry and **Koog's** `LongTermMemory`. A CLI agent brings its own tools and its own context, so it cannot use either — those rounds would stop being about Koog. Round 1 could run on a subscription, but Claude **refuses to play j-claw**: asked to fabricate a conflict it declines and offers an honest counter-offer instead. Round 1 needs a model that will cheerfully claim it staged a calendar event it cannot see, because that lie is the setup for round 2. |
| `identify` | `gemini-3.7-flash` (API key) | Cheap, fast, and the output is a small typed record. Nothing here benefits from a stronger model. |
| `deploy` | **Codex** (subscription) | Deliberately the weakest link: a cheap drafter that will overreach. That is what gives the critic something to catch. |
| `verify` | **Claude** (subscription) | The critic must not be the model that wrote the draft. Different vendor, different weights, different training — and `agents-cli` only supports typed output for `claude`, so it is the only CLI that can return a structured critique. |
| `refine` | `gemini-3.7-flash` (API key) | Applies one specific correction. Cheap is fine. |

**Why 3.7 and not 3.8.** Measured, same pipeline and prompts, three runs each:
3.5 = 60s, 3.6 = 37s, **3.7 = ~20s**, 3.8 = ~30s. Newer is not automatically faster,
and 3.7 was the only one that landed the intended answer on every run. Koog 1.2's
`GoogleModels` catalogue stops at 3.5; 3.6/3.7/3.8 are declared by hand in `Models.kt`,
six lines each, because `LLModel` is just a data class.

**Why cheap drafts and an expensive review.** Constraints enforced by a prompt are
suggestions. Constraints enforced by a separate model that can reject are checks. The
cheap drafter is not a cost saving, it is the thing that makes the critic visibly earn
its place.

**Cost of the subscription steps.** A typed Claude stage measures ~150-220s against
~5s for a Gemini call. Round 4 goes from ~20s to roughly 3 minutes a run. That is a
deliberate trade for "no API pricing on those steps", not an accident.

## SECURITY — read this before you wire any CLI agent

`CliAIAgent` shells out to the CLI **on your machine**, which inherits **your** MCP
servers. `workspace` scopes the filesystem and **not** MCP.

An early build of round 1 ran Claude with `BypassPermissions` and no MCP restriction.
Asked to get someone out of a meeting it read a real calendar, a real TripIt itinerary,
a real work address, and **created a real calendar event**. On a livestream that is
private data on YouTube, live.

If you use a CLI agent anywhere, pass both:

```kotlin
additionalFlags = listOf(
    "--strict-mcp-config",            // only MCP from --mcp-config; pass none
    "--settings", """{"permissions":{"deny":["Bash","Read","Edit","Write","WebFetch","WebSearch","Glob","Grep","Task","NotebookEdit"]}}""",
)
```

Do **not** use `--tools` or `--disallowedTools` for this: both are variadic, so they
swallow the prompt and the run dies with `No result event found`, which looks like a
Koog bug and is not one. `--settings` takes a single argument.

Verify it: ask the agent what is on your calendar. It must answer that it has no
calendar tool.

**Four capability axes, not three:**

- **read** — `getCalendar`, `getOrganizerSensitivity`. No side effects.
- **write** — `createCalendarEvent`. Changes Baruch's world; nobody else sees it.
- **user** — `askBaruch`, `pingBaruch`, `awaitApproval`. Reaches Baruch, interrupts him,
  but nothing leaves the building.
- **comms** — `sendDecline`. Reaches the organizer. Irreversible.

The **user** axis is what makes the slicing argument land. `deploy` gets neither user
nor comms tools, so it commits to a plan in total silence; `verify` is the phase that
can surface and ask. Without a user-facing tool at all, "deploy cannot contact a human"
is true and says nothing.

Three constraints carry the argument. Keep all three:

1. **`deploy` has no communication tools.** It cannot contact a human even if it
   decides to. Enforced by the tool slice, not by asking nicely in a prompt.
2. **The critic runs on a different model than the drafter.** Cheap model drafts,
   expensive model reviews.
3. **Approval is a node; sending is not in the graph.** After the critic approves, an
   `approve` node calls `awaitApproval` and blocks on a real human — a node, not a tool
   the model may or may not decide to call. Only then does the application call
   `sendDecline`. The irreversible action is never the model's call, and the ping
   always happens.

**Bound the refine loop.** Ours stops after 2 refusals and ships the last draft. An
unbounded critic is a hang, and we hit it.

## The A/B — build this, it is the most important two minutes

An env flag (`JCLAW_NAIVE=1` on our side) that strips `recentlyUsedFlavors` out of the
handoff and removes the user context. **Same pipeline, same models, same tools —
poorer data.**

| Mode | Observed | Time |
|---|---|---|
| domain-modelled | `ALREADY_PROFICIENT`, approved first pass | ~55-90s |
| naive | stages *"Family Obligation"* — burned — critic rejects, refine fixes it | ~2m |

This is where the talk's thesis becomes visible rather than asserted. It must work on
your side too.

## Optional: the cross-vendor critic (Baruch's side only, cuttable)

`JCLAW_CRITIC=cli` swaps the critic from Gemini onto **Claude Code running on a local
subscription**, via Koog 1.1.1's `CliAIAgent` — `apiKey = null` is what makes it use the
login rather than a key. Gemini drafts, Claude judges, and what crosses between the two
vendors is a typed data class.

You do **not** need to build an equivalent. It is a cuttable flourish on one side, it
costs ~4 minutes against ~90s, and nothing downstream depends on it. It is described
here only so you know what is on screen if it runs.

## Optional on the Koog side: observability

- `./gradlew graph` emits `pipeline.mmd` **from the live strategy object** via Koog's
  `asMermaidDiagram()`. At JNation the diagram was hand-drawn and Baruch had to say so
  on stage; this one cannot drift from the code.
- Setting `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` / `LANGFUSE_HOST` installs the
  OpenTelemetry feature with the Langfuse exporter. Absent the keys it is a no-op, so
  the demo never depends on a network service.

Neither is required for parity.

## The ending

The excuse the pipeline should land on is `ALREADY_PROFICIENT`: *"I build AI agents for
a living and I am presenting a live conference talk on exactly this topic that
afternoon."*

That is **true**, and the audience is watching it be true. It is the payoff of the whole
talk. Give the phases the user context that makes it reachable, and have the critic
prefer excuses that are literally true.

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

## Verification before you call it done

1. All four rounds run clean from a cold start, in order, inside their target runtimes
2. Round 2 reuses a burned excuse **every time**, not sometimes
3. Round 3 stops reusing, and names the burned flavors in its output
4. Round 4 lands `ALREADY_PROFICIENT`, and the naive mode fails then recovers
5. `sendDecline` fires **only** after human confirmation
6. Your MCP tool-call trace is visible and legible at streaming resolution
7. Time every round. If any is more than ~20% slower than the table above, say so —
   the beat plan has 5 minutes of slack and no more

## Open questions — Viktor decides, not you

1. Which LC4J Agentic constructs? `@SequentialAgent` + `@ConditionalAgent` is the
   natural fit for the loop, but it is HIS CALL.
2. LC4J memory equivalent, pointed at the same three seeded prior declines.
3. A `VIKTOR_CLASSIC` flavor variant, or one shared enum?
4. Which models per phase. Ours: Gemini 3.5 Flash drafts, Gemini 3.1 Pro reviews.
5. Whether your side uses the shared `:tui` module. The TamboUI three-pane UI from the
   JNation build is in this repo and compiles unchanged against tamboui 0.4.0 (now a
   Central release, no longer a snapshot). Baruch's round 4 has both front ends:
   `./gradlew run` (stdout) and `./gradlew runTui` on branch `round4`. Visual parity across
   the two sides matters more than which one you pick — pick the same one.
