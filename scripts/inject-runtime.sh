#!/usr/bin/env bash
# inject-runtime.sh — (re-)inject scripts/runtime.cljs into the running
# browser runtime. Returns the health map from `re-frame-pair.runtime/health`.
#
# Usage:
#   scripts/inject-runtime.sh [--build=:app]
set -euo pipefail
case "${1:-}" in --help|-h) sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;; esac
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" inject "$@"
