# Implementation status

A living record of what's actually implemented, what's scaffolded, and what's blocked on the spike. Updated per release. See `docs/initial-spec.md` for the design this is measured against.

**Last updated:** 2026-04-20 (pre-spike)

---

## TL;DR

| Area | State |
|---|---|
| Design spec | Complete (see `docs/initial-spec.md`) |
| `SKILL.md` | Written — the full vocabulary Claude learns |
| `scripts/runtime.cljs` | Written — helpers for every op in §4, plus health & sentinel |
| `scripts/ops.clj` + shell shims | Written — babashka dispatches every op |
| `.claude-plugin/plugin.json` | Written |
| `package.json` + GH Actions (CI + release) | Written |
| `tests/runtime/` unit tests | Scaffolded (`runtime_test.cljs`) |
| `tests/fixture/` sample app | Not yet — see `tests/fixture/README.md` for scope |
| End-to-end against a live re-frame app | Not done — this is the spike |

**Nothing in this repo has been exercised against a running shadow-cljs build yet.** Pre-alpha. See *Known unknowns* below.

---

## Per-phase status (against `docs/initial-spec.md` §6)

| Phase | Deliverable | State | Notes |
|---|---|---|---|
| 0 | `eval-cljs.sh` round-trips a form | **Coded, not yet run** | `scripts/eval-cljs.sh` + `ops.clj` implement it; needs a live nREPL to verify. |
| 1 | Read surface (§4.1) | **Coded** | `app-db/snapshot`, `app-db/get`, `app-db/schema`, `registrar/list`, `registrar/describe`, `subs/live`, `subs/sample` — all in `runtime.cljs` + SKILL.md. |
| 2 | Dispatch + trace (§4.2–§4.3) | **Coded, 10x internals stubbed** | `tagged-dispatch!`, `tagged-dispatch-sync!`, `dispatch-and-collect` are in place; the 10x epoch-buffer reader is a stub that returns `[]` until the spike identifies the real accessor. |
| 3 | Live watch (§4.4) | **Coded, pull-mode only** | `scripts/watch-epochs.sh` runs repeated short evals at 100ms cadence; streaming-via-`:out` transport deferred to spike. |
| 4 | Hot-swap (REPL) | **Coded** | Delivered by `reg-event`/`reg-sub`/`reg-fx` via `eval-cljs.sh`. |
| 5 | Hot-reload coordination (§4.5) | **Coded** | `tail-build.sh` implements the probe-based protocol — nREPL polling rather than actual server-stdout tailing. Soft fallback at 300ms when no probe. |
| 6 | Time-travel (§4.6) | **Stubbed** | Every `undo/*` op returns `{:ok? false :reason :not-yet-implemented}`. Adapter over 10x internals is the last spike deliverable. |
| 7 | Diagnostics recipes (§4.7) | **Coded as SKILL.md procedures** | Listed; will be refined as real usage surfaces needed ops. |
| 8 | Packaging | **Coded** | `package.json`, `plugin.json`, GH Actions for CI + npm release on tag. See `RELEASING.md`. |

---

## Known unknowns — the §8a spike deliverables

Four things need to be proven against a minimal fixture before calling this beyond pre-alpha:

### 1. Runtime discovery

`scripts/discover-app.sh` needs to actually connect. Specific unknowns:

- **nREPL port location.** We try `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`, `.nrepl-port`, and `$SHADOW_CLJS_NREPL_PORT` env var in that order. Which path is the actual shadow-cljs default in current versions should be confirmed.
- **CLJS mode switch.** `(shadow.cljs.devtools.api/cljs-eval <build-id> <form-str> {})` is the entry. Does `babashka.nrepl-client/eval-expr` return the `:value` in a parseable edn form, or wrapped in a shadow-specific result map?

### 2. CLJS eval round-trip

Does `scripts/eval-cljs.sh '(+ 1 2)'` return `{:ok? true :value 3}`? If not, `ops.clj`'s `cljs-eval-value` parsing needs adjustment.

### 3. 10x epoch-buffer extraction

The load-bearing unknown. `runtime.cljs`'s `read-10x-epochs` is currently a stub that returns `[]`. The spike should:

1. Identify the actual 10x internal name (candidates: `day8.re-frame-10x.metamorphic/epochs>`, `day8.re-frame-10x.navigation.epochs.subs`, or a `day8.re-frame-10x.db` atom).
2. Confirm the epoch record shape — what fields does 10x already assemble, and what does `runtime.cljs`'s `coerce-epoch` need to derive?
3. Verify this is achievable **without registering a second `register-trace-cb`** — the single-source-of-truth claim from spec §3.2.

### 4. Live-watch transport

`ops.clj`'s `watch` subcommand implements **pull-mode** today — each poll is its own short eval. This works but is chatty. Spec §4.4 also sketches a streaming-via-`:out` approach which is genuinely uncertain in CLJS (async `prn` during a go-loop may not reach the session's `:out` binding).

The spike should conclusively show whether streaming works reliably. If yes, upgrade `ops.clj`. If no, lock in pull-mode and update the spec.

### 5. re-com `:src` format

`runtime.cljs`'s `parse-rc-src` assumes `data-rc-src` is a `"file:line"` or `"file:line:column"` string. Actual format on current re-com needs verification; update the parser accordingly.

### 6. Time-travel adapter (§4.6)

10x's internal epoch navigation is not a public API. The spike needs to identify the events/subs 10x uses for its own stepping UI (candidates in `day8.re-frame-10x.navigation.epochs.events`) and wire `undo-step-back` etc. to dispatch into 10x's internal bus. Until then, `undo/*` returns `:not-yet-implemented`.

---

## What's genuinely verified

- `re-frame.db/app-db`, `re-frame.registrar/kind->id->handler`, `re-frame.trace/register-trace-cb`, `re-frame.trace/trace-enabled?` exist and are reachable (confirmed in public re-frame source).
- shadow-cljs nREPL accepts JVM `(shadow.cljs.devtools.api/cljs-eval ...)` calls (well-known).
- re-com's `:src (at)` convention exists (re-com.config + re-com.debug).

Everything else is structurally correct per the spec but not runtime-verified.

---

## Next actions

In order:

1. Build `tests/fixture/` — the minimal re-frame + 10x + re-com + shadow-cljs app the spike runs against.
2. Ground-truth the six items under *Known unknowns*.
3. Adjust `runtime.cljs` and `ops.clj` to match.
4. Wire `tests/runtime/` into an actual shadow-cljs test build.
5. Graduate out of pre-alpha and cut `v0.1.0-beta.1`.
