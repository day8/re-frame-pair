#!/usr/bin/env bash
# tail-build.sh — coordinate with shadow-cljs hot-reload after a source edit.
#
# --wait-ms N       how long to wait for the reload to land (default 5000)
# --probe '<form>'  a CLJS form whose return value changes after the edit is live.
#                   When the form's return value flips, reload has landed.
#                   Without --probe, falls back to a fixed 300ms timer delay
#                   and returns :soft? true.
#
# Examples:
#   # Wait up to 5s, using a probe that checks the new handler's hash
#   scripts/tail-build.sh --probe '(re-frame-pair.runtime/registrar-handler-ref :event :cart/apply-coupon)'
#
#   # Soft fallback (no probe)
#   scripts/tail-build.sh
#
# Note on the name: despite being called tail-build, this does NOT tail
# shadow-cljs's server stdout. Both the server-side "Build complete" event
# and the browser-side reload landing are driven through the probe-based
# pattern. See docs/initial-spec.md §4.5 for rationale.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" tail-build "$@"
