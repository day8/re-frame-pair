#!/usr/bin/env bash
# trace-recent.sh — return epochs added to the runtime's buffer in
#                   the last N ms. Maps to SKILL.md `trace/recent`.
#
# Usage:
#   scripts/trace-recent.sh 3000         # last 3 seconds of epochs
#   scripts/trace-recent.sh 30000 --build=:app
set -euo pipefail
case "${1:-}" in --help|-h) sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;; esac
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" trace-recent "$@"
