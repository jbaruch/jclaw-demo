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

### 3. Four rounds, four runnable entry points

Each must be independently launchable — on stage they are run one at a time, in order.

| Round | Must demonstrate | Target runtime |
|---|---|---|
| 1 | A chatbot in one construction call. Answers well, can do nothing | ~15s |
| 2 | Tools + both MCP servers. It acts — and **reuses a burned excuse** | ~45s |
| 3 | Memory installed. Same prompt, and it stops reusing | ~40s |
| 4 | Typed subtask pipeline with a critic and a refine loop | ~90s |

Round 4 runs **three** times on Baruch's side inside its 21 minutes: the clean
domain-modelled run (~90s), the naive run that fails and recovers (~2m), and an
optional cross-vendor critic (~4m, the designated cut line). Parity is only required
for the first two.

Round 2 **must fail** in the specific way described: the agent picks an excuse already
used on Dana. Do not prompt it away from that. The failure motivates round 3, and if
your side succeeds where the Koog side fails, the bake-off has no spine.

### 4. The pipeline shape (round 4)

```
identify --> deploy --> verify --(approved)--> done
               ^           |
               +-- refine <+  (rejected, with feedback)
```

| Phase | In → Out | Tools available |
|---|---|---|
| `identify` | `String` → `DeclineRequest` | read only |
| `deploy` | `DeclineRequest` → `DeclineDeployment` | read + write, **no comms** |
| `verify` | `DeclineDeployment` → critique | read only |
| `refine` | feedback → `DeclineDeployment` | read + write |

Read = `getCalendar`, `getOrganizerSensitivity`. Write = `createCalendarEvent`.
Comms = `sendDecline`.

Three constraints carry the argument. Keep all three:

1. **`deploy` has no communication tools.** It cannot contact a human even if it
   decides to. Enforced by the tool slice, not by asking nicely in a prompt.
2. **The critic runs on a different model than the drafter.** Cheap model drafts,
   expensive model reviews.
3. **Sending is NOT in the graph.** The agent returns a critic-approved plan; the
   application calls `sendDecline` after a human confirms. The irreversible action is
   never the model's call.

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
   `:round4-pipeline:run` (stdout) and `:round4-pipeline:runTui`. Visual parity across
   the two sides matters more than which one you pick — pick the same one.
