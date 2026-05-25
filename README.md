# j-claw

A personal AI agent that declines speaker dinner invitations. Built twice — once in **[Koog](https://github.com/JetBrains/koog)**, once in **[LangChain4j Agentic](https://github.com/langchain4j/langchain4j)** — for the JNation 2026 talk *"Codepocalypse Now (Age of Agents): Koog vs LangChain4J Agentic."*

Same domain, same mocks, same TUI, side-by-side on the projector. The point of the talk is that agentic Java is real now, regardless of which framework you pick.

## What's in this repo

```
jclaw-demo/
├── SPEC.md              ← authoritative demo spec (mocks, types, pipeline, beat plan)
├── mocks/
│   ├── calendar-mcp/    ← stdio MCP server returning canned calendar events
│   ├── organizer-mcp/   ← stdio MCP server logging decline messages
│   └── memory.sqlite    ← pre-seeded chat-history database
├── tui/                 ← shared TamboUI shell — chat / trace / prompt panes
├── jclaw-koog/          ← Baruch's Koog implementation (4 progressive rounds)
└── jclaw-lc4j/          ← Viktor's LangChain4j Agentic implementation (4 progressive rounds)
```

## The four rounds (each a runnable Gradle module)

| Round | What it shows |
|---|---|
| `round1-chatbot` | Basic `AIAgent(...)` one-liner — chatbot, no tools, no memory |
| `round2-tools-mcp` | Sliced `ToolSet`s + mock MCP servers — agent picks an overused excuse and fails verification (no memory yet) |
| `round3-memory` | + ChatMemory backed by `mocks/memory.sqlite` — agent sees prior declines, avoids reusing flavors |
| `round4-pipeline` | + domain-modeled subtask pipeline — `identifyDecline` → `deployDecline` → `verifyDecline` → `refineDecline` with typed I/O and sliced tools per phase |

Round 4 is the headline. The progressive structure exists so the audience can see what each capability adds.

## Run

Requires JDK 17+ and Gradle 8+. API keys for OpenAI and Anthropic via environment variables:

```bash
export OPENAI_API_KEY=...
export ANTHROPIC_API_KEY=...

# Koog side
./gradlew :jclaw-koog:round4-pipeline:run

# LC4J side
./gradlew :jclaw-lc4j:round4-pipeline:run
```

Both apps spawn the mock MCP servers locally — no network setup, no real Telegram, no real calendar. The TUI is a TamboUI terminal interface; type your prompt, watch the agent work.

## Tessl tiles used

- [`jbaruch/koog`](https://tessl.io/registry/jbaruch/koog) — Koog 1.0 idioms, gotchas, and skills (`domain-model-subtask-pipeline` is what this demo demonstrates)
- [`jbaruch/tamboui`](https://tessl.io/registry/jbaruch/tamboui) — TamboUI rules for the TUI layer

## License

Apache 2.0 — see `LICENSE`.

## The talk

JNation 2026 · Aveiro, Portugal · 2026-05-26 · Baruch Sadogursky ([@jbaruch](https://twitter.com/jbaruch)) + Viktor Gamov ([@gamussa](https://twitter.com/gamussa))

Shownotes: `speaking.jbaru.ch/codepocalypse-jnation-2026`
