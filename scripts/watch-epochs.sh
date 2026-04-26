#!/usr/bin/env bash
# watch-epochs.sh — pull-mode live watch of 10x's epoch buffer.
#
# Emits one edn line per matching epoch, plus a final {:finished?} summary.
#
# Modes:
#   --window-ms N    Run for N ms, report matches, summarise.
#   --count N        Run until N matches emitted.
#   --stream         Run until disconnect, idle (default 30s), or --hard-ms.
#   --stop           Telemetry stub; terminate the running process instead.
#
# Predicates (any combination, AND-ed):
#   --event-id :foo
#   --event-id-prefix :cart/
#   --effects :http-xhrio
#   --timing-ms '>100' or '<5'
#   --touches-path [:a :b]
#   --sub-ran :cart/total
#   --render 'my.ns/foo'
#
# Output filters:
#   --dedupe-by :event   Suppress consecutive epochs whose :event vec
#                        matches the previous emitted one — useful with
#                        --stream against handlers that fire many times
#                        in a row.
#
# Stopping defaults: idle-ms 30000, hard-ms 300000, count 5.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" watch "$@"
