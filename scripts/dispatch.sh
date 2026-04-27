#!/usr/bin/env bash
# dispatch.sh — fire a re-frame event in the connected app.
#
# Default mode is queued dispatch (`rf/dispatch`) — same path app code uses.
# --sync forces `rf/dispatch-sync` for deterministic before/after.
# --trace uses re-frame.core/dispatch-and-settle (rf-4mr): sync +
# adaptive quiet-period wait for the full :fx [:dispatch ...] cascade
# + return root and cascaded epochs. Falls back to dispatch-sync +
# fixed sleep + collect-after-dispatch when re-frame predates rf-4mr.
# --stub <fx-id> (rf-ge8 / dispatch-with) substitutes a record-only
# stub for the named fx (and any cascade) so HTTP / navigation /
# local-storage etc. don't actually fire — the captured effect values
# land in the session log readable via
# `(re-frame-pair.runtime/stubbed-effects-since <ts>)`. Repeatable for
# multiple fx.
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
