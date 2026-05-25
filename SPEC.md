# j-claw Demo Spec — JNation 2026

**Status:** DRAFT — handoff to Viktor for LangChain4j-Agentic side
**Talk:** Codepocalypse Now (Age of Agents) — JNation 2026, 2026-05-26
**Slot:** 45 minutes, co-presented
**Presenters:** Baruch Sadogursky (Koog side) / Viktor Gamov (LangChain4j Agentic side)

---

## 1. Premise

Both presenters build the **same agent** — a personal AI assistant called **j-claw** — that helps the user decline speaker dinner invitations. The user (Baruch on his side, Viktor on his) is a serial decliner; the agent's job is to draft a fresh excuse, stage backing evidence in the calendar, and notify the organizer — without reusing an excuse from any of the user's previous dinners.

Same scenario, same domain model, same mock backend, same audience-visible interface — two frameworks. **The point of the talk: agentic Java is real, and both Koog and LangChain4j Agentic produce essentially the same app.** The Java developer wins regardless of which side they pick.

Comedy hook: **j-claw is the agent Baruch actually needs.** Built for an audience of conference speakers, declining the JNation speaker dinner ON STAGE during the JNation speaker dinner timeslot. The organizer (Roberto Cortez) is also in the audience. The j-claw character is J.Lo–adjacent; specific touchpoints in §10.

---

## 2. Shared mock MCP servers

Both apps consume the **same two mock MCP servers** running locally on the demo laptop. The mocks live in `mocks/` of this repo; Viktor's app launches them via stdio the same way Baruch's does.

### 2.1 `calendar-mcp`

Stdio MCP server. Tools:

```
getCalendar() → List<CalendarEvent>
createCalendarEvent(title: String, startIso: String, durationMin: Int) → String   // returns eventId
```

Canned `getCalendar()` payload (same for both apps — read once, served fresh each call):

```json
[
  { "id": "jnation-2026-dinner", "title": "JNation Speaker Dinner", "start": "2026-05-26T20:00:00+01:00", "organizer": "Roberto Cortez", "declined": false },
  { "id": "jnation-2025-dinner", "title": "JNation Speaker Dinner",  "start": "2025-06-19T20:00:00+01:00", "organizer": "Roberto Cortez", "declined": true, "declineReason": "EMERGENCY_MEETING" },
  { "id": "devoxx-2025-dinner", "title": "Devoxx BE Speaker Dinner", "start": "2025-10-09T20:00:00+02:00", "organizer": "Stephan Janssen", "declined": true, "declineReason": "FAMILY_OBLIGATION" },
  { "id": "spring-io-2026-dinner", "title": "Spring I/O Speaker Dinner", "start": "2026-04-15T20:00:00+02:00", "organizer": "Sergi Almar", "declined": true, "declineReason": "HOTEL_ISSUE" }
]
```

`createCalendarEvent` echoes back a fresh `eventId` and logs the title to stderr for the trace pane.

### 2.2 `organizer-mcp`

Stdio MCP server. Tools:

```
getOrganizerSensitivity(name: String) → Sensitivity     // EASYGOING | NORMAL | TOUCHY
sendDecline(eventId: String, message: String) → DeclineReceipt   // { delivered: true, deliveredAt: ISO }
```

Canned sensitivity map: `Roberto Cortez → EASYGOING`. Anyone else → `NORMAL`. (Reserved: a `TOUCHY` entry for live audience-Q&A bit if we have time.)

### 2.3 Implementation note

Both stubs are **~60 lines of Kotlin / Java each**, stdio JSON-RPC, no real network. Baruch's repo will provide them; Viktor's app imports them as-is. **Do not reimplement the mocks on the LC4J side — share these binaries so behavior is identical.**

---

## 3. Shared domain types

Both apps produce these typed shapes. Viktor's framework may use different annotations (`@LLMDescription` on Koog side; LC4J Agentic has its own annotation set — Viktor picks the equivalent). The **schemas must match** so that the JSON the LLM produces on each side is interchangeable.

```kotlin
enum class ExcuseFlavor {
    EMERGENCY_MEETING,      // overused — see canned calendar
    FAMILY_OBLIGATION,      // overused — see canned calendar
    DEADLINE,               // unused, available
    HOTEL_ISSUE,            // overused — see canned calendar
    JET_LAG_HONEST,         // unused, available — the "good" answer
    BARUCH_CLASSIC,         // see §10 — comedy answer
}

enum class PlausibilityTier { AIRTIGHT, CREDIBLE, THIN, JENNY_FROM_THE_BLOCK }

@LLMDescription("A request to decline a social obligation on the user's behalf")
data class DeclineRequest(
    @property:LLMDescription("Calendar event the user is declining") val eventId: String,
    @property:LLMDescription("Excuse flavors used at this venue or with these attendees in the last 90 days") val recentlyUsedFlavors: List<ExcuseFlavor>,
    @property:LLMDescription("Names of people attending who can corroborate or contradict a story") val knownAttendees: List<String>,
    @property:LLMDescription("Who organized the event — they will receive the decline") val organizerName: String,
)

@LLMDescription("A decline drafted, staged, and ready to send")
data class DeclineDeployment(
    @property:LLMDescription("Excuse flavor selected") val flavor: ExcuseFlavor,
    @property:LLMDescription("Calendar event id created to back the story") val fakeCalendarEventId: String,
    @property:LLMDescription("The decline message that will go to the organizer") val messageToOrganizer: String,
    @property:LLMDescription("Hallway script — what to say if the organizer asks the next day") val hallwayScript: String,
)

// Verification result type — Koog has CriticResult<T>; Viktor's LC4J equivalent must carry the same fields
data class VerifyResult<T>(
    val successful: Boolean,
    val feedback: String?,
    val input: T,
)
```

---

## 4. Shared subtask pipeline

Four phases. Same shape, same models, same tool subsets on both sides.

| Phase | Input → Output | Tools available | Model |
|---|---|---|---|
| `identifyDecline` | `String` → `DeclineRequest` | `userTools` + `readTools` | GPT-5.2 (cheap) |
| `deployDecline` | `DeclineRequest` → `DeclineDeployment` | `readTools` + `writeTools` (NO user-facing tools — silent commit) | Sonnet 4 (mid-tier) |
| `verifyDecline` | `DeclineDeployment` → `VerifyResult<DeclineDeployment>` | `userTools` + `readTools` | O3 (reasoning) |
| `refineDecline` | `String` (feedback) → `DeclineDeployment` | `readTools` + `writeTools` | Sonnet 4 |

Edges:

```
start          → identifyDecline
identifyDecline → deployDecline
deployDecline   → verifyDecline
verifyDecline   → finish     when successful, emitting .input
verifyDecline   → refineDecline   when !successful, emitting .feedback
refineDecline   → verifyDecline
```

The **`deployDecline` not having user-facing tools is intentional** and narratively load-bearing: the agent commits to a plan silently, then `verifyDecline` is the one that pings the user with "✅ confirm? 👍/👎". Tool slicing has narrative consequence — don't soften it on the LC4J side.

### Koog side
`subgraphWithTask<In, Out>` for the three regular phases, `subgraphWithVerification<DeclineDeployment>` for `verifyDecline` (it produces `CriticResult<DeclineDeployment>` automatically).

### LC4J Agentic side
Equivalent shape using `@SequentialAgent` chain with `@ConditionalAgent` for the verify/refine loop. Viktor knows the LC4J idioms; the contract this spec enforces is the **typed I/O of each phase** and the **tool subset granted per phase**.

---

## 5. Shared tool slice

Three `ToolSet`s on each side. The names are flexible; the **slice** (read/write/communication) is not.

| ToolSet | Capability axis | Tools |
|---|---|---|
| `UserTools(userTelegramId)` | Communication with the user only | `askUser`, `pingUserPrivate`, `awaitReaction` (returns 👍 or 👎) |
| `ReadTools` | Read (no side effects) | `readCalendar` (calendar-mcp), `searchPriorExcuses` (memory), `getAttendeeList` (canned), `getOrganizerContact` (organizer-mcp) |
| `WriteTools` | Write (real consequences) | `createCalendarEvent` (calendar-mcp), `sendDeclineToOrganizer` (organizer-mcp) |

Constructor parameters (`userTelegramId`, MCP client handles, memory provider) come via DI — **the LLM never sees them.** Both sides enforce this.

---

## 6. Memory contract

Both sides install chat memory backed by **the same shared SQLite file** at `mocks/memory.sqlite`, pre-seeded with the three prior declines (matching the canned calendar in §2.1).

- Koog: `install(ChatMemory) { chatHistoryProvider = SqliteChatHistoryProvider(...) }`
- LC4J: equivalent chat-memory feature pointing at the same sqlite file

The pre-seeded memory contains plain-language entries like:
- `"Declined JNation 2025 — told Roberto it was an emergency meeting"`
- `"Declined Devoxx 2025 — told Stephan it was a family obligation"`
- `"Declined Spring I/O 2026 — told Sergi the hotel had a problem"`

`searchPriorExcuses()` queries this memory; the LLM uses the result to populate `DeclineRequest.recentlyUsedFlavors`.

**Why this matters for the demo:** memory is what makes the agent's job *hard* (must pick a fresh excuse). The pre-seeded sqlite means the demo works on first run with no warm-up.

---

## 7. TUI interface contract

Both apps present a **TamboUI** terminal UI (Java TUI library — `jbaruch/tamboui` tile in Tessl registry). Same library on both sides for visual parity; pull `dev.tamboui:tamboui-toolkit:LATEST` (snapshot).

### Layout — three panes

```
┌─ j-claw ─────────────────────────────────────────────────┐
│  CHAT                                                    │  ← user types, agent replies here
│                                                          │
├──────────────────────────────────────────────────────────┤
│  TRACE                                                   │  ← subtask names, tool calls, models, durations
│  ▸ identifyDecline   GPT-5.2     0.8s   ✓               │
│    └ searchPriorExcuses → 3 hits                         │
│  ▸ deployDecline      Sonnet 4   1.4s   ✓               │
│  ▸ verifyDecline      O3          ⟳                      │
├──────────────────────────────────────────────────────────┤
│  PROMPT                                                  │  ← input + 👍/👎 reaction
│  > _                                                     │
└──────────────────────────────────────────────────────────┘
```

### Behavior

- CHAT pane shows the conversation. Plain text, scrolls
- TRACE pane is driven by Koog's `handleEvents { ... }` / LC4J's equivalent event hooks — every subtask entry/exit and every tool call appears as a new line
- PROMPT pane is the only input surface. **Keyboard `y` = 👍, keyboard `n` = 👎.** The agent's `awaitReaction` tool blocks on this keystroke
- Window background: terminal default. Header color: pick a brand color per side — Baruch's side green (Koog), Viktor's side yellow (LC4J)

### Render-thread discipline
Per `jbaruch/tamboui` tile rule `render-thread-discipline`: all UI mutations on the render thread; from agent callbacks use `runOnRenderThread { ... }`. The Tessl tile auto-loads this rule.

---

## 8. 45-minute beat plan

Both apps must be in the corresponding state at each beat. Sync points marked **⇄**.

| Time | Beat | Koog side | LC4J side |
|---|---|---|---|
| 0:00–4:00 | Cold open + side-picking | — | — |
| 4:00–9:00 | **Round 1 — chatbot in 5 min** ⇄ | Build basic `AIAgent(...)` one-liner, prompt → text reply | Build basic LC4J one-liner, same shape |
| 9:00–10:00 | Pivot: *"this isn't an agent"* | — | — |
| 10:00–18:00 | **Round 2 — j-claw v1: tools + mock MCPs** ⇄ | Register `UserTools` + `ReadTools` + `WriteTools`; wire calendar-mcp + organizer-mcp; agent picks an overused flavor and verify FAILS (no memory yet) | Same, in LC4J |
| 18:00–23:00 | **Round 3 — j-claw v2: memory** ⇄ | Install ChatMemory pointing at `mocks/memory.sqlite`; re-run same prompt; agent now sees prior declines and avoids them | Same |
| 23:00–25:00 | **Tessl Secret Weapon interlude** | Baruch flashes the koog tile + tamboui tile that produced both apps | — |
| 25:00–37:00 | **Round 4 — domain modeling + subtooling (THE HEADLINE)** ⇄ | Refactor to four-subtask pipeline. Show `DeclineRequest`/`DeclineDeployment` on slide. Run full identify → deploy → verify → refine loop live. Picks `BARUCH_CLASSIC` first (laugh), fails verify (`JENNY_FROM_THE_BLOCK` tier), refines to `JET_LAG_HONEST`, passes, sends | Same loop in LC4J |
| 37:00–40:00 | **The verdict** | Comparison table (both win) | — |
| 40:00–43:00 | **The winner is the Java developer** | — | — |
| 43:00–45:00 | **Bookend** — Agent Johnson + j-claw declines a final invite from the audience | — | — |

**Both apps must reach each ⇄ sync point at the same wall-clock time** so the side-by-side comparison reads cleanly. Build artifacts at each level (basic / tools / memory / pipeline) as separate Gradle source-sets or branches so we can switch instantly on stage without recompiling.

---

## 9. Recommended Gradle module layout

Each side a separate Gradle multi-module project. Suggested layout (Viktor picks his own — but matching makes screen-shares easier):

```
jclaw-lc4j/   (Viktor's repo or directory)
├── settings.gradle.kts
├── round1-chatbot/         ← basic agent
├── round2-tools-mcp/       ← + tool sets + mock MCPs
├── round3-memory/          ← + ChatMemory
├── round4-pipeline/        ← + four-phase pipeline (THE headline build)
├── tui/                    ← shared TamboUI shell
└── mocks/                  ← symlinked or copied from Baruch's mocks/
```

Each round is a runnable `application` plugin entry point. On stage we run `./gradlew :round4-pipeline:run` and the TUI fires up against the mocks.

---

## 10. J.Lo touchpoints (verbatim)

These must be identical on both sides so the jokes land in stereo.

### System prompt opener (both sides)

> *You are j-claw. You help [Baruch / Viktor] decline speaker dinners. Don't be fooled by the rocks that they got — they're still [Baruch / Viktor] from the block, and they don't want to go.*

### `ExcuseFlavor.BARUCH_CLASSIC` verbal output

When this flavor is picked, `messageToOrganizer` must contain the literal string `"I have to go take care of Jenny."` and the `fakeCalendarEventId` must come from a `createCalendarEvent` call whose title is `"Jenny — block"`.

### `PlausibilityTier.JENNY_FROM_THE_BLOCK`

The worst tier. `verifyDecline` returns `successful = false` whenever the deployment scores this tier. Audience sees the enum value on the slide in §3 and on the trace pane during Round 4.

### Viktor-equivalent

For Viktor's app the user is Viktor. His `BARUCH_CLASSIC` flavor is renamed `VIKTOR_CLASSIC` with verbal `"I have to go check on the cold start times"` and calendar title `"Quarkus — block"`. **Both apps' `JENNY_FROM_THE_BLOCK` tier name stays the same** (it's the shared joke, not user-specific).

That's the J.Lo budget. Don't add more.

---

## 11. What Viktor owns

- LC4J Agentic-specific code (annotation choices, agent composition, feature install syntax)
- Picking the LC4J `ChatMemory` equivalent (LC4J has its own; must point at the same sqlite file)
- The LC4J-equivalent of `subgraphWithTask` / `subgraphWithVerification` (`@SequentialAgent` + `@ConditionalAgent` is the natural fit but Viktor chooses)
- His Gradle module structure (suggestion in §9; not mandatory)
- His TUI integration if he picks something other than TamboUI (strongly recommend TamboUI — visual parity is the demo's whole point)

## 12. What Baruch owns

- The two mock MCP servers (calendar-mcp, organizer-mcp) — Viktor links to the binaries, doesn't reimplement
- The pre-seeded `mocks/memory.sqlite` — Viktor reads it, doesn't reimplement
- The TamboUI TUI shell — Baruch ships a reusable `tui/` module; Viktor wires his agent into it
- The shared spec (this document) — updates require both sides' sign-off

---

## 13. Sync plan

- **By 18:00 today:** mocks + sqlite + TUI shell pushed to `~/Projects/jclaw-demo/` and shared with Viktor (rsync, git, dropbox, whatever)
- **By 22:00 today:** both Round 1 builds running independently against the mocks. Smoke test via screen-share
- **By 09:00 tomorrow:** Round 4 (the headline) running on both sides. Joint dry-run of the full 45-minute beat plan
- **Speaker-room sync, 30 min before stage:** display sanity check, MCP server health check, sqlite memory pre-seed verified

If any round slips, fall back to **Round 3 on both sides + recorded Round 4 video** as insurance. This is the "live for Round 4, recorded for the rest" fallback the original talk spec already flagged.

---

## 14. Open questions for Viktor

- Are you OK with TamboUI for the TUI, or do you want to use something else (jline, lanterna, etc.)? If different, parity on the visual layout still required
- Do you have a preferred LC4J `ChatMemory` provider that can read the sqlite file Baruch ships, or do we need a tiny adapter?
- Do you want your enum to be `VIKTOR_CLASSIC` or pick something different — your call on the personal-joke flavor as long as the shape matches
- Any conference-Q&A bits you want to seed in `organizer-mcp` (e.g., audience members named in the canned organizer list)?

Reply with your decisions on these four; everything else above is locked.

---

**Locked at:** 2026-05-25 (T-1 day to show time)
**Baruch:** confirmed
**Viktor:** awaiting
