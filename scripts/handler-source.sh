#!/usr/bin/env bash
# handler-source.sh — file:line of a registered handler.
#
# Reads {:file :line} from the meta attached by re-frame's reg-*
# macros (rf-ysy, commit 15dfc25): every reg-event-{db,fx,ctx} /
# reg-sub / reg-fx capture *file* + (:line (meta &form)) at expansion
# time and with-meta the location onto the registered value
# (interceptor vector for :event, fn for :sub / :fx). Returns
# :no-source-meta cleanly when re-frame predates rf-ysy or when
# registration went through reg-*-fn programmatic variants that
# don't capture &form.
#
# Usage:
#   scripts/handler-source.sh :event :cart/apply-coupon
#   scripts/handler-source.sh :sub :cart/total
#   scripts/handler-source.sh :fx :http-xhrio
set -euo pipefail
case "${1:-}" in --help|-h) sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;; esac
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" handler-source "$@"
