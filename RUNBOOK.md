# j-claw — stage runbook

IntelliJ IDEA Conf 2026 · Day 1, 15:00–16:00 CEST · Baruch (Koog) + Viktor (LangChain4j Agentic)


## Never `gradle run`. Use `./jclaw`.

`gradle run` does not return. Measured, with and without the daemon: the app JVM
exits, the mock JVMs exit, and Gradle sits there with three live processes until you
Ctrl-C it. Nothing in the application can fix this — it was verified by instrumenting
the exit path and then watching the process table while Gradle hung.

**The JNation build hit this too.** Its `run.sh` says, verbatim: *"runs the agent
binary DIRECTLY (no `gradle run`)"*. `./jclaw` is the same thing: Gradle builds, the
installed start script runs. Faster as well, for skipping Gradle startup.

## Before you go live — WARM EVERY BRANCH

The rounds are branches (`round1`..`round4`), one `app/src/main/kotlin/jclaw/Main.kt`
that changes underneath you. Switch off-camera while Viktor is presenting.

**The first run on a branch after a checkout recompiles.** A cold branch took over 6
minutes; warm it is seconds. Warm all four before the stream:

```bash
for b in round1 round2 round3 round4; do
  git checkout $b && ./gradlew -q :app:installDist
done
git checkout round1
```

Do not skip this. It is the difference between a 20-second demo and dead air.

**Use `./jclaw`, never `gradle run`.** `gradle run` never returns: the app exits
correctly but the MCP mocks inherit Gradle's stderr and Gradle waits on it forever, so
the terminal hangs after every successful round. `./jclaw` runs the installed start
script instead — same work, no hang, and faster for skipping Gradle startup.

`./jclaw` always rebuilds. `build/` is gitignored and survives a checkout, so a
"binary already exists" shortcut would silently run the PREVIOUS round.

## Before you go live

```bash
export GOOGLE_API_KEY=...          # from vault secrets.json -> gemini.api_key
cd ~/Projects/jclaw-ideaconf
gradle :mocks:mcpJars              # builds calendar-mcp.jar + organizer-mcp.jar
# (warming, above, already builds every branch)
```

Warm the Gradle daemon and the Maven cache with one throwaway `:round1-chatbot:run`
before the stream starts. A cold first run adds ~40s of dependency resolution that
is not interesting to watch.

## The four rounds

| Round | Command | Runtime | What the audience should see |
|---|---|---|---|
| 1 | `git checkout round1 && ./jclaw` | ~16s | One factory call. Charming, useless. |
| 2 | `git checkout round2 && ./jclaw` | ~10s | Tool trace. Stages a fake meeting and sends. **Reuses a burned excuse.** |
| 3 | `git checkout round3 && ./jclaw` | ~8s | Same prompt. Names the burned flavors, picks fresh — and invents a category nothing checks. |
| 4 | `git checkout round4 && ./jclaw` | ~25s | Typed pipeline, sliced tools, critic, approval node. `ALREADY_PROFICIENT`. |
| 4-TUI | `./jclaw tui` | ~25s | **Three-pane UI.** Subtask boundaries and tool calls in a TRACE pane. |
| 4b | `JCLAW_NAIVE=1 ./jclaw` | ~35s | Constraint stripped. Reaches for a burned excuse. **Critic catches it, refine fixes it.** |
| 4c | `JCLAW_CRITIC=cli ./jclaw` | ~4m | Critic is Claude Code on subscription. Showpiece, and the designated cut line. |
| graph | `./jclaw graph` | ~1s | Emits `pipeline.mmd` **from the live strategy**. |
| 5 | `JCLAW_LEVEL=4 ./jclaw skills` | ~19s | Discovers SKILL.md on disk, reads it on screen, applies it. |
| 5b | `JCLAW_LEVEL=11 ./jclaw skills` | ~19s | Same skill at 11. Unreadable. Every clause still true. |

### The cross-vendor critic (optional showpiece)

`JCLAW_CRITIC=cli` swaps the critic from Gemini 3.1 Pro to **Claude Code, running on
Baruch's subscription** via Koog 1.1.1's `CliAIAgent` — no API key anywhere in the
config. Gemini drafts the excuse, Claude decides whether it survives People Ops, and
the handoff between two vendors is a typed data class.

**It takes about 4 minutes** (vs ~90s for the Gemini critic) because the CLI has
per-invocation startup and needs extra turns to produce schema-shaped JSON. Verified
working end to end; approved `ALREADY_PROFICIENT` on the first pass.

Use it only if the clock is healthy, and narrate the architecture while it runs. The
Gemini critic is the default for a reason.

A critic that returns nothing parseable is treated as a **rejection**, not an
approval. Fail closed.

**The approval gate is a graph node.** After the critic approves, `approve` calls
`awaitApproval` and blocks on you — type `y` in the terminal (or the prompt pane in the
TUI). It is not a tool the model may skip; it always fires.

`JCLAW_AUTOSEND=1` answers it for you and skips the send gate — use it only if you are short
on time. The gate is a talking point: the model never sends anything.

## Round 4 — the A/B, in order

Run the **naive** one first if you want the critic to earn its keep on camera:

```bash
JCLAW_NAIVE=1 ./gradlew run     # fails, critic rejects, refine fixes
./gradlew run                   # clean, first-pass approval
```

Ask the chat to predict what breaks *before* you run the naive one. It is the only
audience-participation beat that works without a room.

## If something dies on stage

- **MCP server won't start** → `gradle :mocks:mcpJars` was not run, or the jars are
  stale. The error names the exact missing path.
- **`GOOGLE_API_KEY is not set`** → the Gradle daemon did not inherit your export.
  Re-export and re-run; `--no-daemon` guarantees it.
- **Round 4 runs long** → it is bounded at 2 critic refusals and then ships the last
  draft, printing `critic still unhappy after 2 refinements`. It cannot hang.
- **Anything else** → round 3 is the safe fallback. It is visually similar to round 4
  and always completes in 40s.

## The TUI build — VERIFY THIS ON A REAL TERMINAL FIRST

`./gradlew runTui` runs round 4 inside the TamboUI three-pane UI kept
from the JNation build (chat pane, trace pane, prompt input, busy spinner). It compiles
against tamboui 0.4.0 unchanged and starts without error, **but it has only been smoke
tested under a pseudo-terminal, never driven by hand.** Run it once in your actual
terminal at your actual streaming font size before you rely on it.

Both env flags work here too (`JCLAW_NAIVE`, `JCLAW_CRITIC`). The send gate is the word
`send` typed into the prompt pane rather than `y`.

If it misbehaves on the day, `./gradlew run` is the same pipeline on
stdout and is the build that has been dry-run repeatedly.

## Which model, and why

Default is **`gemini-3.7-flash`**, chosen by measuring rather than by version number.
Same pipeline, same prompts:

| model | round 4 |
|---|---|
| gemini-3.5-flash | 60s |
| gemini-3.6-flash | 37s |
| **gemini-3.7-flash** | **~20s** |
| gemini-3.8-flash | ~30s |

Newer is not automatically faster — 3.8 is consistently slower than 3.7 — and 3.7 was
the only one that picked `ALREADY_PROFICIENT` on every run, naive runs included.
Override with `JCLAW_FLASH=3.8 ./jclaw`.

Koog 1.2's `GoogleModels` stops at 3.5; 3.6/3.7/3.8 are declared in `Models.kt`,
six lines each. Worth saying out loud: the framework shipped ten days ago and already
trails the models it talks to, and you are one declaration away from catching up.

## Known behaviour, not bugs

- The critic usually approves round 4 on the first pass. That is the architecture
  working — the typed handoff carries the constraint. Use `JCLAW_NAIVE=1` to show
  the failure.
- Gemini occasionally staged *two* calendar events in one deploy pass. Harmless; the
  critic picks the one that covers the session.
- `agents-cli`, `agents-mcp`, the Google client, `skills` and the memory feature are
  all on Koog's **beta** version line (`1.2.0-beta`), not `1.2.0`. Worth saying out
  loud — it is an honest read of where the framework is.
