# jclaw-koog

Baruch's side. j-claw in **[Koog](https://github.com/JetBrains/koog)** 1.0+.

## Modules (each runnable)

| Module | What it shows |
|---|---|
| `round1-chatbot/` | Basic `AIAgent(...)` factory — text in, text out |
| `round2-tools-mcp/` | + sliced `ToolSet`s + mock MCP servers via `McpToolRegistryProvider` |
| `round3-memory/` | + `ChatMemory` feature pointing at `mocks/memory.sqlite` |
| `round4-pipeline/` | + four-phase domain-modeled subtask pipeline — `subgraphWithTask<In, Out>` + `subgraphWithVerification<T>` |

## Run

```bash
export OPENAI_API_KEY=...
export ANTHROPIC_API_KEY=...
./gradlew :jclaw-koog:round4-pipeline:run
```

## Tessl tiles

Authored against the [`jbaruch/koog`](https://tessl.io/registry/jbaruch/koog) tile — specifically the `domain-model-subtask-pipeline` skill for Round 4.
