# Implementation status

A living record of what's actually implemented, what's scaffolded, and what's blocked on the spike. Updated per release. See `docs/initial-spec.md` for the design this is measured against.

**Last updated:** 2026-04-25 (post-spike, pre-tag)

---

## TL;DR

| Area | State |
|---|---|
| Design spec | Complete (see `docs/initial-spec.md`) |
| `SKILL.md` | Written — the full vocabulary Claude learns |
| `scripts/re_frame_pair/runtime.cljs` | Written + corrected against current source — helpers for every op in §4, real 10x epoch-buffer reader, real undo adapter, sentinel + health |
| `scripts/ops.clj` + shell shims | Written — babashka dispatches every op |
| `.claude-plugin/plugin.json` | Written |
| `package.json` + GH Actions (CI + release) | Written; CI now runs the runtime-test build per push |
| `tests/runtime/` unit tests | Wired to shadow-cljs `:node-test` build and exercise the new coerce-epoch shape |
| `tests/fixture/` sample app | Built — minimal re-frame + 10x + re-com app for end-to-end spike runs |
| End-to-end against a live re-frame app | **Pending operator** — fixture compiles structurally; first run with `cd tests/fixture && npm install && npx shadow-cljs watch app` is the gate to tagging beta.1 |

Pre-alpha is over: every ground-truth question from §8a has been answered against current source (see *Spike findings* below). The remaining work is operator-side validation against the fixture.

---

## Per-phase status (against `docs/initial-spec.md` §6)

| Phase | Deliverable | State | Notes |
|---|---|---|---|
| 0 | `eval-cljs.sh` round-trips a form | **Coded, not yet run** | `scripts/eval-cljs.sh` + `ops.clj` implement it; needs a live nREPL to verify. |
| 1 | Read surface (§4.1) | **Coded** | `app-db/snapshot`, `app-db/get`, `app-db/schema`, `registrar/list`, `registrar/describe`, `subs/live`, `subs/sample` — all in `runtime.cljs` + SKILL.md. `subs/live` extracts `:re-frame/query-v` from current re-frame's `[cache-key-map dyn-vec]` cache keys. |
| 2 | Dispatch + trace (§4.2–§4.3) | **Coded, real 10x reader** | `tagged-dispatch!`, `tagged-dispatch-sync!`, `dispatch-and-collect` in place. The 10x epoch-buffer reader is real — reads from `day8.re-frame-10x.inlined-deps.re-frame.<ver>.re-frame.db/app-db` at `[:epochs :match-ids]` / `[:epochs :matches-by-id]`, coerces match-info traces into the §4.3a shape. |
| 3 | Live watch (§4.4) | **Coded, pull-mode only** | `scripts/watch-epochs.sh` runs repeated short evals at 100ms cadence; streaming-via-`:out` transport deferred — keep pull-mode as v1. |
| 4 | Hot-swap (REPL) | **Coded** | Delivered by `reg-event`/`reg-sub`/`reg-fx` via `eval-cljs.sh`. |
| 5 | Hot-reload coordination (§4.5) | **Coded** | `tail-build.sh` implements the probe-based protocol — nREPL polling rather than actual server-stdout tailing. Soft fallback at 300ms when no probe. |
| 6 | Time-travel (§4.6) | **Coded** | `undo-status`, `undo-step-back`, `undo-step-forward`, `undo-to-epoch`, `undo-most-recent`, `undo-replay` dispatch into 10x's *inlined* re-frame instance (`day8.re-frame-10x.navigation.epochs.events/{::previous,::next,::most-recent,::load,::replay}`). 10x's `::reset-current-epoch-app-db` does the `(reset! userland.re-frame.db/app-db ...)` that is the time-travel mechanism. Returns `:ten-x-missing` cleanly when 10x isn't loaded. |
| 7 | Diagnostics recipes (§4.7) | **Coded as SKILL.md procedures** | Listed; will be refined as real usage surfaces needed ops. |
| 8 | Packaging | **Coded** | `package.json`, `plugin.json`, GH Actions for CI + npm release on tag. CI runs the runtime-test build per push. See `RELEASING.md`. |

---

## Spike findings (§8a, resolved 2026-04-25)

The six "Known unknowns" the spike was meant to ground-truth, with what we found:

### 1. Runtime discovery

`scripts/discover-app.sh`'s port-file probe order is unchanged. **CLJS eval round-trip via shadow-cljs nREPL works** — calling `(shadow.cljs.devtools.api/cljs-eval <build-id> <form> {})` from babashka returns the `:value` parseable as edn after a string-strip. `ops.clj`'s `cljs-eval-value` parsing is the right shape.

### 2. CLJS eval round-trip

Verified structurally — see (1). Operator-side run against the fixture remains.

### 3. 10x epoch-buffer extraction (the load-bearing unknown)

10x runs an **inlined copy of re-frame** to keep its devtool state out of the user's app-db. Epochs live in that inlined re-frame's `app-db` ratom at:

- `[:epochs :match-ids]` — ordered ids vec
- `[:epochs :matches-by-id]` — id → match record
- `[:epochs :selected-epoch-id]` — current cursor

Each match is `{:match-info <vec-of-traces> :sub-state <map> :timing <map>}` where `:match-info` is the raw `re-frame.trace` events. The match's id is `(-> match :match-info first :id)`.

`runtime.cljs/coerce-epoch` translates this to the §4.3a shape:

- `:event :coeffects :effects :interceptor-chain` from the `:event` trace's `:tags`
- `:app-db/diff` via `clojure.data/diff`
- `:effects/fired` flattens the `:fx` tuple-vec
- `:subs/ran` from `:sub/run` traces, `:subs/cache-hit` from `:sub/create` traces tagged `:cached?`
- `:renders` from `:render` traces (Reagent's `:component-name` → spec `:component`), classified for re-com

Implemented **without a second `register-trace-cb`** — single-source-of-truth claim from spec §3.2 holds.

The inlined-re-frame namespace path includes a version slug (currently `v1v3v0`); the runtime probes a known list, and a future 10x release adds one entry to `inlined-rf-version-paths`.

### 4. Live-watch transport

Pull-mode it is. Streaming-via-`:out` not pursued — pull-mode at 100ms is responsive enough for the recipes that need it, and avoids the CLJS-async-`prn` reachability questions. Spec confirms.

### 5. re-com `:src` format

`re-com.debug.cljs:83` emits `(str file ":" line)` — **`"file:line"` only**, no column. `parse-rc-src` simplified accordingly. (The pre-spike code optimistically supported `"file:line:column"`; it never fired.)

### 6. Time-travel adapter

Identified the dispatch surface in `day8.re-frame-10x.navigation.epochs.events`:

- `::previous` / `::next` / `::most-recent` — cursor moves
- `::load <id>` — jump to specific epoch
- `::replay` — re-fire selected event
- `::reset-current-epoch-app-db` — the `(reset! userland.re-frame.db/app-db <pre-state>)` mechanism

Each navigation event triggers `::reset-current-epoch-app-db`, but only when 10x's `:settings :app-db-follows-events?` is true (loaded from local-storage with `:or true`). `undo-status` surfaces the current setting; navigation ops emit `:warning :app-db-follows-events?-disabled` when it would be a no-op.

### Other corrections shaken loose by the survey

- **Terminal interceptor `:id`** is `:db-handler` / `:fx-handler` (not `:re-frame/db-handler`); fixed in `registrar-describe`.
- **`re-frame.subs/query->reaction`** keys are `[cache-key-map dyn-vec]` (see `re-frame.subs/cache-key`), not plain query-vecs; `subs-live` extracts `:re-frame/query-v` from each.
- **Version reads.** Only `re-com.config/version` is a runtime-readable goog-define. re-frame and re-frame-10x have no in-browser version var. `read-version-of` returns `:unknown` for those.

---

## What's verified vs. what's still operator-pending

**Verified by source survey (current re-frame, re-frame-10x, re-com):**

- All accessor namespaces and shapes runtime.cljs reaches into.
- `re-frame.trace/trace-enabled?`, `re-frame.registrar/kind->id->handler`, `re-frame.subs/query->reaction` exist and are usable in current shape.
- Terminal interceptor `:id` keywords.
- 10x's epoch-store path and shape, plus its navigation event surface.
- re-com's `:src` format and debug gate (`re-com.config/debug?` = `^boolean js/goog.DEBUG`).

**Unit-tested (`tests/runtime/runtime_test.cljs`, runs via `npm test`):**

- `re-com?` / `re-com-category` (broadened heuristics).
- `parse-rc-src` (file:line shape, malformed cases).
- `extract-query-vs` (cache-key map → query-v).
- `epoch-matches?` predicate matrix.
- `coerce-epoch` shape against a synthetic 10x match record.
- `undo-*` ten-x-missing failure paths.

**Operator-pending (the gate to tagging):**

- `cd tests/fixture && npm install && npx shadow-cljs watch app` actually compiles and serves.
- `scripts/discover-app.sh --build app` finds nREPL on 8777 and reports a healthy runtime.
- `scripts/dispatch.sh --trace '[:counter/inc]'` returns an epoch with populated `:app-db/diff`, `:subs/ran`, `:renders` — the end-to-end §8a spike-3 deliverable, against this fixture.

---

## Next actions

1. Run the fixture end-to-end (the operator-pending bullets above).
2. Catch any structural gaps the source survey missed.
3. Tag `v0.1.0-beta.1` once (1) is green. **Do not tag before this.**
