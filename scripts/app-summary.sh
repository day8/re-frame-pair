#!/usr/bin/env bash
# app-summary.sh — one-call session-bootstrap bundle: versions,
# registrar inventory, live subs, app-db top-level shape, and health.
#
# Recommended as the first call after `discover-app.sh` for context-
# bootstrapping recipes — replaces 5+ separate round-trips.
#
# Usage:
#   scripts/app-summary.sh
set -euo pipefail
case "${1:-}" in --help|-h) sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;; esac
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" app-summary "$@"
