#!/usr/bin/env bash
# dispatch.sh — fire a re-frame event in the connected app.
#
# Default mode is queued dispatch (`rf/dispatch`) — same path app code uses.
# --sync forces `rf/dispatch-sync` for deterministic before/after.
# --trace uses dispatch-and-collect: sync + wait an animation frame + return
# the resulting epoch by id.
#
# Usage:
#   scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]'
#   scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]' --sync
#   scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]' --trace
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" dispatch "$@"
