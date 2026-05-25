#!/usr/bin/env bash
# j-claw end-to-end launcher. Builds both mock MCP servers, then runs the agent.
#
# Setup (one time):
#   cp .env.example .env
#   # edit .env with your real OPENAI_API_KEY and ANTHROPIC_API_KEY
#
# Run:
#   ./run.sh
#   ./run.sh "RSVP no to the JNation 2026 speaker dinner. Different excuse than last time."
#
# Requires JDK 17+ in PATH. Mocks are built on first run; subsequent runs are fast.
set -euo pipefail

cd "$(dirname "$0")"

# Auto-source .env if it exists (overridable by already-set env vars)
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

if [[ -z "${OPENAI_API_KEY:-}" || -z "${ANTHROPIC_API_KEY:-}" ]]; then
  echo "✘ Both OPENAI_API_KEY and ANTHROPIC_API_KEY must be set." >&2
  echo "  cp .env.example .env  &&  \$EDITOR .env" >&2
  echo "  -- or --" >&2
  echo "  export OPENAI_API_KEY=sk-..." >&2
  echo "  export ANTHROPIC_API_KEY=sk-ant-..." >&2
  exit 1
fi

if [[ $# -gt 0 ]]; then
  ./gradlew --console=plain :jclaw-koog:run --args="$*"
else
  ./gradlew --console=plain :jclaw-koog:run
fi
