# HANDOFF — jclaw-koog

## State

| Module | Build | Notes |
|---|---|---|
| `mocks/calendar-mcp` | ✅ | Stdio-tested. Koog `@Tool`/`@LLMDescription` + `startStdioMcpServer` |
| `mocks/organizer-mcp` | ✅ | Same shape |
| `jclaw-koog` | ✅ | Full Round-4 pipeline. Compiles + `installDist` runs |
| TUI shell | ⏸ | Deferred; using stdout/stdin for now |
| seed-memory | n/a | In-process `SeedMemory.priorDeclines`; sqlite swap is optional |

## Tessl tiles installed

This repo has `jbaruch/koog` and `jbaruch/tamboui` installed (`.tessl/tiles/`). The rules auto-load when an AI coding agent is invoked in this directory; skills load on demand. **Use them if you're iterating with Claude Code or another agent** — they encode the right Koog 1.0 idioms and saved me an hour of import-path archaeology on this build.

## To run end-to-end

```bash
export OPENAI_API_KEY=...
export ANTHROPIC_API_KEY=...
./gradlew :mocks:calendar-mcp:installDist :mocks:organizer-mcp:installDist
./gradlew :jclaw-koog:run --args="RSVP no to the JNation 2026 speaker dinner. Different excuse than last time."
```

The mocks must be installed (`installDist`) before `jclaw-koog` runs because `Main.kt` launches them as subprocesses via `ProcessBuilder("./mocks/calendar-mcp/build/install/calendar-mcp/bin/calendar-mcp")`.

## Things to verify on first run

- `AnthropicModels.Sonnet_4` exists in 1.0 (used as the deploy/refine model). If not, swap to `Opus_4_6` or `Opus_4_7` — both confirmed available
- `OpenAIModels.Chat.O3` exists for the verifier. If not, fall back to `GPT4o`
- The MCP servers' tool descriptors come through — check the agent's first LLM round-trip's tool schema includes `getCalendar`, `createCalendarEvent`, `getOrganizerSensitivity`, `sendDecline`

## Next steps

1. **Smoke-run** Round 4 end-to-end with real API keys
2. **Branch backward** — once Round 4 is good on `main`:
   - `git checkout -b round3-memory` → delete `Strategy.kt`, switch `Main.kt` to `singleRunStrategy()`; keep tools + memory + MCP
   - `git checkout -b round2-tools-mcp main` → also delete `Memory.kt`, drop `priorDeclines` arg from `ReadTools`, remove `searchPriorExcuses`
   - `git checkout -b round1-chatbot main` → strip all tools; just `AIAgent(promptExecutor, llmModel, systemPrompt).run(input)`
3. **TamboUI** — wrap stdout/stdin in three-pane `tui/` module (chat / trace / prompt). Pull `jbaruch/tamboui` tile's `scaffold-toolkit-app` skill
4. **Demo dry-run** — joint sync with Viktor per `SPEC.md` §13

## Demo-defensible right now

Even with TUI deferred:

- Two Koog-built MCP servers responding on stdio — itself a talking point ("Koog server = same `@Tool` annotations as Koog client")
- A full Round-4 pipeline that compiles and produces typed `DeclineDeployment` output
- A repo on GitHub with public visibility — linkable from shownotes
- A spec ready for Viktor
- Two installed Tessl tiles (`jbaruch/koog`, `jbaruch/tamboui`) as the "Secret Weapon" interlude prop
