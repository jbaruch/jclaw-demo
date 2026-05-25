# Mocks

Two stdio MCP servers + a pre-seeded SQLite memory database. Both Koog and LangChain4j Agentic implementations consume the same binaries — do not reimplement the mocks per framework.

## `calendar-mcp/`

stdio MCP server. Exposes:

- `getCalendar() → List<CalendarEvent>` — returns canned fixture in `calendar-mcp/fixture.json`
- `createCalendarEvent(title, startIso, durationMin) → eventId` — echoes a fresh `eventId` and logs the title to stderr (visible in the TUI trace pane)

See `SPEC.md` §2.1 for the exact canned payload.

## `organizer-mcp/`

stdio MCP server. Exposes:

- `getOrganizerSensitivity(name) → Sensitivity` — `EASYGOING` for `Roberto Cortez`, `NORMAL` otherwise
- `sendDecline(eventId, message) → DeclineReceipt` — logs sends, returns `{ delivered: true, deliveredAt: ISO }`

See `SPEC.md` §2.2.

## `memory.sqlite`

Pre-seeded chat-history database with three prior declines (JNation 2025 / Devoxx 2025 / Spring I/O 2026). Both apps install a chat-memory feature pointing at this file. Pre-seeding is what makes the Round 3 "agent avoids reusing flavors" demo work on first run.

Seed script: `seed-memory.sh` (TODO). Schema follows the Koog `ChatHistoryProvider` JDBC default — see `SPEC.md` §6.
