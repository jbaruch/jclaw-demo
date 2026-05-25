#!/usr/bin/env bash
# j-claw end-to-end launcher.
# Builds both mock MCP servers + the agent (Gradle), then runs the agent binary
# DIRECTLY (no `gradle run`) so stdout streams live instead of being buffered by Gradle.
#
# Setup (one time):
#   cp .env.example .env
#   $EDITOR .env       # paste OPENAI_API_KEY and ANTHROPIC_API_KEY
#
# Run:
#   ./run.sh
#   ./run.sh "RSVP no to the JNation 2026 speaker dinner. Different excuse than last time."
set -euo pipefail

cd "$(dirname "$0")"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

if [[ -z "${OPENAI_API_KEY:-}" || -z "${ANTHROPIC_API_KEY:-}" ]]; then
  echo "✘ Both OPENAI_API_KEY and ANTHROPIC_API_KEY must be set." >&2
  exit 1
fi

# Build everything (mocks installDist + jclaw-koog installDist)
./gradlew --console=plain --quiet \
  :mocks:calendar-mcp:installDist \
  :mocks:organizer-mcp:installDist \
  :jclaw-koog:installDist

# Run the agent binary directly — unbuffered stdout
exec ./jclaw-koog/build/install/jclaw-koog/bin/jclaw-koog "$@"
