# jclaw-lc4j

Viktor's side. j-claw in **[LangChain4j Agentic](https://github.com/langchain4j/langchain4j)**.

Viktor owns this directory — his framework, his idioms, his code. The contract with Baruch's `jclaw-koog/` is the shared `mocks/`, the shared `tui/` shell, and the typed subtask pipeline schemas in `SPEC.md` §3–§5.

## Modules (each runnable — same shape as the Koog side)

| Module | What it shows |
|---|---|
| `round1-chatbot/` | Basic LC4J agent — text in, text out |
| `round2-tools-mcp/` | + sliced tool classes + mock MCP servers |
| `round3-memory/` | + chat memory pointing at `mocks/memory.sqlite` |
| `round4-pipeline/` | + four-phase pipeline (`@SequentialAgent` + `@ConditionalAgent`, or equivalent) matching `SPEC.md` §4 |

## Run

```bash
export OPENAI_API_KEY=...
export ANTHROPIC_API_KEY=...
./gradlew :jclaw-lc4j:round4-pipeline:run
```

## Open questions for Viktor

See `SPEC.md` §14 — four decisions Viktor needs to make before Round 1 can start on this side.
