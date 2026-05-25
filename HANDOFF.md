# HANDOFF — jclaw-koog completion

## State as of this commit

| Module | Build status | Smoke-tested |
|---|---|---|
| `mocks/calendar-mcp` | ✅ builds clean | ✅ stdio protocol responds correctly |
| `mocks/organizer-mcp` | ✅ builds clean | (not yet) |
| `jclaw-koog` | ❌ compile errors — see below | — |
| `tui/` | not started — using println for now | — |

## What was scaffolded for `jclaw-koog`

- `Domain.kt` — `DeclineRequest`, `DeclineDeployment`, `ExcuseFlavor`, `PlausibilityTier` with `@LLMDescription` annotations matching `SPEC.md` §3
- `Tools.kt` — `UserTools` (askBaruch, pingBaruchPrivate, awaitReaction) + `ReadTools` (searchPriorExcuses backed by `SeedMemory.priorDeclines`)
- `Memory.kt` — in-process `SeedMemory` object with three canned prior declines (avoids sqlite + ChatMemory feature install for the demo build; swap later if needed)
- `Strategy.kt` — four-subgraph pipeline shape (identify → deploy → verify → refine) with edges
- `Main.kt` — wires MCP subprocesses, local tools, strategy, agent

## Open compile errors — to fix locally with IDE assistance

### 1. `McpToolRegistryProvider.fromProcess` import

It exists as a `suspend` extension function:

```kotlin
public suspend fun McpToolRegistryProvider.fromProcess(process: Process, ...): ToolRegistry
```

Confirmed package: `ai.koog.agents.mcp`. The call site in `Main.kt` writes `McpToolRegistryProvider.fromProcess(...)` directly, but since it's an EXTENSION function on the companion object, the right syntax may need either:

```kotlin
import ai.koog.agents.mcp.fromProcess
val reg = McpToolRegistryProvider.fromProcess(process)
```

or invoked via a companion-object reference. Try both, see which compiles.

### 2. `AIAgentGraphStrategy` import path

Current (wrong): `ai.koog.agents.core.agent.AIAgentGraphStrategy`
Correct: `ai.koog.agents.core.agent.entity.AIAgentGraphStrategy`

### 3. `forwardTo` / `onCondition` packages

Not confirmed. Try `ai.koog.agents.core.dsl.builder.forwardTo` and `ai.koog.agents.core.dsl.builder.onCondition` first; IDE auto-complete will find them.

### 4. `CriticResult` import

Not yet located in the 1.0.0 source. It's referenced by `subgraphWithVerification` returning `AIAgentSubgraphDelegate<Input, CriticResult<Input>>`. Likely package: `ai.koog.agents.ext.agent` (same as `subgraphWithVerification`). IDE auto-complete will resolve.

### 5. `MultiLLMPromptExecutor` / `OpenAILLMClient` / `AnthropicLLMClient` packages

Best guesses:
- `ai.koog.prompt.executor.llms.MultiLLMPromptExecutor`
- `ai.koog.prompt.executor.clients.openai.OpenAILLMClient`
- `ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient`

The umbrella `koog-agents:1.0.0` should pull all of these. IDE auto-import.

### 6. Anthropic / OpenAI model entries

`AnthropicModels.Sonnet_4_5` may not be the exact name. Available 1.0 entries include `Opus_4_7`, `Opus_4_6`, `Sonnet_4`, etc. — check `AnthropicModels.*` autocomplete. Same for `OpenAIModels.Chat.*`.

### 7. `calendarRegistry.tools` field

Used in `Main.kt` to filter MCP tools by name into read vs write sets. `ToolRegistry.tools` may not be a public property — might need `.allTools` or `.toolNames` or similar. Adjust based on IDE introspection.

## Next steps once it compiles

1. **Smoke test Round 4** — set `OPENAI_API_KEY` + `ANTHROPIC_API_KEY`, run `./gradlew :jclaw-koog:run` with a sample prompt. Watch the agent step through identify → deploy → verify → refine
2. **Pre-build the mock binaries** — `./gradlew :mocks:calendar-mcp:installDist :mocks:organizer-mcp:installDist` before any run, so `Main.kt`'s `ProcessBuilder` paths resolve
3. **Round-back-branches** — once `main` works, create:
   - `git checkout -b round3-memory` — delete `Strategy.kt`, replace `Main.kt`'s strategy wiring with `singleRunStrategy()`; keep tools + memory + MCP
   - `git checkout -b round2-tools-mcp main` then `git rm Memory.kt`, drop `priorDeclines` argument from `ReadTools`, delete the `searchPriorExcuses` tool
   - `git checkout -b round1-chatbot main` then strip tools entirely; just `AIAgent(promptExecutor, llmModel, systemPrompt).run(input)`
4. **TamboUI** — wrap stdout in a TamboUI three-pane app per `tui/README.md` once the agent flow is solid

## What's working today and demo-defensible

Even before the j-claw side compiles, the demo has:

- Two real MCP servers written in Koog (`@Tool`/`@LLMDescription`) that respond on stdio — these are themselves a talking point: "We can write MCP servers in Koog too, with the same annotations agents use to call them."
- A pre-built repo on GitHub with public visibility — for shownotes
- A spec ready for Viktor

If the talk lands before the j-claw pipeline is fully wired, the worst case is doing Rounds 1-3 with the simpler shape and showing the Round 4 pipeline as code-on-slide. The data class definitions in `Domain.kt` already read well on a slide.
