#!/usr/bin/env bash
# handler-source.sh — file:line of a registered handler.
#
# Reads the metadata that shadow-cljs's source-map machinery attaches
# to compiled fns. For :event handlers, drills into the terminal
# interceptor's :before fn (the user-written reg-event-{db,fx,ctx}
# body). Returns :no-source-meta cleanly when the compile mode didn't
# populate fn metadata.
#
# Usage:
#   scripts/handler-source.sh :event :cart/apply-coupon
#   scripts/handler-source.sh :sub :cart/total
#   scripts/handler-source.sh :fx :http-xhrio
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" handler-source "$@"
