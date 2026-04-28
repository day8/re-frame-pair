#!/usr/bin/env bash
# console-tail.sh — read js/console.{log,warn,error,info,debug} entries
# captured by the runtime's console wrapper, optionally filtered.
#
# Usage:
#   scripts/console-tail.sh                          # all entries
#   scripts/console-tail.sh --since-id 42            # entries with id >= 42
#   scripts/console-tail.sh --who claude             # only :claude-tagged
#   scripts/console-tail.sh --who handler-error      # synthesised :handler-throw entries
#   scripts/console-tail.sh --since-id 42 --who app  # incremental tail for user code
set -euo pipefail
case "${1:-}" in --help|-h) sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;; esac
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" console-tail "$@"
