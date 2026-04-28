#!/usr/bin/env bash
# discover-app.sh — locate shadow-cljs nREPL, verify prerequisites,
# inject re-frame-pair.runtime. Prints a structured edn result.
#
# Usage:
#   scripts/discover-app.sh [--build=:app]
#   scripts/discover-app.sh --list   # list all candidate ports + active builds
#                                    # without injecting; surfaces multi-build
#                                    # setups so you can pick deliberately
set -euo pipefail
case "${1:-}" in --help|-h) sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;; esac
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" discover "$@"
