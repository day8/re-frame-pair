# Implementation status

A living record of what's actually implemented, what's scaffolded, and what's blocked on the spike. Updated per release. See `docs/initial-spec.md` for the design this is measured against.

**Last updated:** 2026-04-27 (post-rfp-3es — native epoch path + new dispatch / source surfaces on `main`)

---

## TL;DR

| Area | State |
|---|---|
| Design spec | Complete (see `docs/initial-spec.md`) |
| `SKILL.md` | Written — the full vocabulary Claude learns |
| `scripts/re_frame_pair/runtime.cljs` | Written + corrected against current source — helpers for every op in §4, native epoch + trace ring buffers (`re-frame/register-epoch-cb`, rf-ybv) with 10x fallback, real undo adapter, sentinel + health |
| `scripts/ops.clj` + shell shims | Written — babashka dispatches every op |
| `.claude-plugin/plugin.json` | Written |
| `package.json` + GH Actions (CI + release) | Written; CI now runs the runtime-test build per push |
| `tests/runtime/` unit tests | **70 deftests / 371 assertions / 0 failures**; shadow-cljs `:node-test` build, run via `npm test`, gated in CI |
| `tests/ops_smoke.bb` babashka tests | **20 deftests / 36 assertions / 0 failures**; ops.clj load-path + pure-helper coverage, run via `npm run test:ops`, gated in CI |
| `tests/fixture/` sample app | Built — minimal re-frame + 10x + re-com app; bundled bootstrap + re-com CSS for self-contained rendering; wired into `re-frame-debux` via `:local/root` (rfp-mkf) so the worked example for the form-by-form trace recipe carries a non-nil `:debux/code` |
| End-to-end against a live re-frame app | **Verified** — full §4.3a epoch shape (event, diff, effects, coeffects, interceptor-chain, subs/ran, subs/cache-hit, renders, timing) produced for UI clicks; all 5 predicate filters validated; time-travel rolls userland app-db correctly. v0.1.0-beta.1 + beta.2 squash-merged to `main` (PRs #1, #2). |

v0.1.0-beta.2 is on `main` (un-tagged). All §8a ground-truth unknowns are resolved (see *Spike findings* below), the runtime + fixture are validated end-to-end, and CI is green on both PRs. Significant work has landed since the last STATUS refresh — see *Post-spike additions* below — including: Phase 1 + Phase 2 of `re-frame-debux` integration (`:debux/code` field, wrap-handler!/unwrap-handler! recipe, dbg single-form recipe), native epoch path (`re-frame/register-epoch-cb` via rf-ybv replaces 10x as primary epoch source; 10x kept as fallback), `dispatch-and-settle` --trace path (rf-4mr), `dispatch-with --stub` for record-only fx stubs (rf-ge8), `:event/source` (rf-hsl) and `:subscribe/source` + `:input-query-sources` on `:subs/ran` (rf-cna) flattened onto coerced epoch records, and `day8.re-frame-10x.public` preferred over inlined-rf walking (rf1-jum). The next tag will reflect this body of work, not just beta.2.

---

## Per-phase status (against `docs/initial-spec.md` §6)

| Phase | Deliverable | State | Notes |
|---|---|---|---|
| 0 | `eval-cljs.sh` round-trips a form | **Verified** | Round-trip confirmed against the live fixture; `(re-frame-pair.runtime/snapshot)` returns the full app-db. |
| 1 | Read surface (§4.1) | **Verified** | `snapshot`, `app-db-at`, `schema`, `registrar-list`, `registrar-describe`, `subs-live`, `subs-sample` all callable against the fixture. `registrar-describe` post-fix returns full `:by-kind` map (rfp-l7m A); `subs-live` correctly partitions ran-vs-cache-hit subs after a UI click. |
| 2 | Dispatch + trace (§4.2–§4.3) | **Verified** | `dispatch.sh --trace` returns the user-fired event's epoch with all §4.3a fields populated (rfp-fgm + rfp-l7m C); chained `:dispatch` effects no longer corrupt the returned epoch; `:claude-epoch-count` increments correctly on tagged dispatches. Post-beta.2: --trace switched to `re-frame.core/dispatch-and-settle` (rf-4mr / rfp-4ew) — adaptive quiet-period replaces the 80ms sleep; `dispatch-with --stub` (rf-ge8 / rfp-zml) lets callers swap HTTP / navigation fx for record-only stubs per dispatch. |
| 3 | Live watch (§4.4) | **Verified** | All 5 predicate filters (`--event-id`, `--event-id-prefix`, `--effects`, `--touches-path`, `--timing-ms`) match expected counts. G3 fix for empty `:touches-path '[]'` confirmed; G5 fix for malformed `--timing-ms` rejection confirmed. Pull-mode at 100ms cadence; streaming-via-`:out` deferred to v0.2. |
| 4 | Hot-swap (REPL) | **Verified** | `reg-event` / `reg-sub` / `reg-fx` via `eval-cljs.sh` work; experiment-loop recipe end-to-end relies on this. |
| 5 | Hot-reload coordination (§4.5) | **Coded** | `tail-build.sh` probe-based protocol implemented; G7 fix surfaces probe-error on timeout instead of silently masquerading as build failure. Live verification across an actual file edit + reload cycle still pending. |
| 6 | Time-travel (§4.6) | **Verified** | `undo-step-back` rolls userland app-db back exactly one step against the fixture; `undo-most-recent` restores; `:app-db-follows-events?` setting surfaces correctly. |
| 7 | Diagnostics recipes (§4.7) | **Verified** | Recipes exercised; SKILL.md tightened in beta.2 (`rfp-mo0`) to remove drift and prune verbose prefaces. |
| 8 | Packaging | **Verified** | CI green on PR #1 (#2). `prepublishOnly` gate added (rfp-czf B); CLAUDE.md / AGENTS.md de-duplicated (rfp-czf C). npm publish OIDC + provenance deferred to v0.2. |

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
- 10x's epoch-store path and shape, plus its navigation event surface — kept as fallback once the native epoch path landed.
- `re-frame/register-epoch-cb` + `assemble-epochs` (rf-ybv) — runtime drains assembled epochs into a native ring buffer; `coerce-native-epoch` translates to §4.3a shape.
- `re-frame.core/dispatch-and-settle` (rf-4mr) — fire-and-await Promise; the runtime wraps it (Promise can't round-trip cljs-eval) and reconstitutes the settled epoch from the native buffer.
- `re-frame.core/dispatch-with` / `dispatch-sync-with` (rf-ge8) — per-dispatch fx-handler substitution via `:re-frame/fx-overrides` event-meta.
- `re-frame.macros/dispatch[-sync]` (rf-hsl) and `re-frame.macros/subscribe` (rf-cna) — both attach `:re-frame/source` to the event-vec / query-v meta at the call site.
- `day8.re-frame-10x.public` (rf1-jum) — public read API (`epochs`, `latest-epoch-id`, `epoch-count`, `all-traces`); preferred over the inlined-rf walk where loaded.
- re-com's `:src` format and debug gate (`re-com.config/debug?` = `^boolean js/goog.DEBUG`).

**Unit-tested (`tests/runtime/runtime_test.cljs` + `tests/runtime/fixtures.cljs`, runs via `npm test`):**

- 70 deftests / 371 assertions / 0 failures (CLJS) + 20 deftests / 36 assertions / 0 failures (babashka, `tests/ops_smoke.bb`).
- `re-com?` / `re-com-category` (broadened heuristics).
- `parse-rc-src` (file:line shape, malformed cases, edge cases).
- `extract-query-vs` (cache-key map → query-v, duplicates, malformed entries).
- `epoch-matches?` predicate matrix (each flag in isolation, sparse epoch fields, falsy vs nil semantics).
- `coerce-epoch` shape against synthetic 10x match records (including orphaned render-burst).
- `coerce-native-epoch` shape against synthetic `assemble-epochs` output; native ring-buffer ingest + drain (`native-epoch-buffer`, `native-trace-buffer`, idempotent cb install, `epoch-by-id`/`last-epoch`/`last-claude-epoch` prefer native then fall back to 10x).
- `subs-ran-from-native-traces` dedupes by query-v; `subs-cache-hit-from-native-traces` honours `:cached?` only.
- `collect-cascade-from-buffer` walks parent-chains by `:dispatch-id` (linear cascade, fan-out, unrelated noise, missing parents).
- `await-settle` state transitions; `dispatch-and-settle!` fallback when re-frame predates rf-4mr; `dispatch-with-stubs!` builds overrides + fallback when re-frame predates rf-ge8.
- `record-only-stub` / `build-stub-overrides` / `stubbed-effects-since` / `clear-stubbed-effects!` (fx-stubs log shape, filter-and-tail semantics).
- `version-below?` semver comparison (mismatched part counts, alpha tags, nil floor/observed).
- `undo-*` ten-x-missing failure paths.
- Synthetic-match fixture helper extracted to `tests/runtime/fixtures.cljs` so 10x shape changes only update one place.
- `:debux/code` surfaces from `:tags :code` when `re-frame-debux`'s `fn-traced` is in play; absent → `nil`. Conditional-free bridge. `dbg-macro-available?` probe (rfd-btn).
- `:event/source` flattened from event-vec meta onto coerced epochs (both 10x and native paths); nil when absent or pre-rf-hsl.
- `:subscribe/source` + `:input-query-sources` flattened onto `:subs/ran` entries; nil for bare-fn subscribes or pre-rf-cna; mixed-meta inputs handled.
- `console-tail-since` filters by id / who / both.
- `tagged-dispatch-sync!` synthesises a handler-error entry, restores `current-who` on catch, and on success.
- `app-summary` shape, app-db one-level-deep coercion.
- `handler-source` across kinds — sub / fx / event (`:db-handler` chain meta) / `:no-source-meta` fallback / not-registered / empty-meta-map.
- `build-id-from-args` accepts both `--build=app` and `--build=:app` forms (`tests/ops_smoke.bb`).

**Verified end-to-end against the live fixture (2026-04-25 / 26):**

- `cd tests/fixture && npm install && npx shadow-cljs watch app` compiles and serves at `:8280`; nREPL on `:8777`.
- `scripts/discover-app.sh` returns `{:ok? true ...}` with all 5 health checks green (`:re-com-debug?`, `:last-click-capture?`, `:ten-x-loaded?`, `:app-db-initialised?`, `:trace-enabled?`).
- `scripts/dispatch.sh --trace '[:counter/inc]'` returns the user-fired event's epoch with every §4.3a field populated.
- All 5 watch predicate filters validated against script-driven dispatches; G3 fix (empty `:touches-path '[]'`) and G5 fix (malformed `--timing-ms` rejection) confirmed.
- Time-travel: `undo-step-back` rolls app-db back exactly one step against the fixture, `undo-most-recent` restores; gated correctly on `:app-db-follows-events?`.
- UI-click epoch shape: `:renders` populated with 21 components annotated by re-com category; `:subs/ran` and `:subs/cache-hit` correctly partition recomputed-vs-unchanged subs.
- Auto-reinject: clearing the session sentinel triggers seamless re-shipping of `runtime.cljs` with `:reinjected? true` flag on the response (no operator action needed after a browser refresh).

---

## Post-spike additions on `main`

Work landed after the §8a ground-truth resolution that's not part of the spec's phase-numbered backbone.

### `rfp-hjj` — Phase 1 `re-frame-debux` integration (2026-04-26)

Surface `re-frame-debux`'s per-form value trace as a first-class field on epoch records — without taking a hard dependency on `day8.re-frame/tracing` and without re-implementing its zipper machinery. See `docs/inspirations-debux.md` for the design rationale; §3.0 (on-demand REPL-driven `fn-traced` wrapping) is the load-bearing recipe. Four commits:

| Item | Commit | Change |
|---|---|---|
| 1 | `3c3c8cd` | `coerce-epoch` surfaces `:debux/code` from `:tags :code`. Absent → `nil`; conditional-free bridge. ~5 LOC. |
| 2 | `09e30ec` | SKILL.md recipe: *Trace a handler/sub/fx form-by-form* — 5-step lookup → `fn-traced` wrap → dispatch → read `:debux/code` → restore. |
| 3 | `29cf2f6` | SKILL.md *Trace* table: row noting `:debux/code` carries per-form trace when `fn-traced` is used. |
| 4 | `3568c11` | `watch-epochs.sh --dedupe-by :event` (debux's `:once` analogue) — silences duplicate consecutive emissions of the same event. |

README also notes `day8.re-frame/tracing` is **not** transitive via `re-frame-10x` (commit `09a9551`).

**Phase 2 deliberately deferred.** The Phase 1 recipe synthesises `(fn-traced [...] body)` macro forms at the REPL. When `re-frame-debux` ships a runtime `wrap-handler!` / `unwrap-handler!` API (queued upstream as `rfd-8g9`), the recipe will switch to that — see *Next actions* below.

### `rfp-bni` — `handler/source` investigation (2026-04-26)

Documented in `docs/handler-source-meta.md` (commit `b8b613b`). Conclusion: `handler-source.sh` reliably hits the `:no-source-meta` path against shadow-cljs builds because re-frame's interceptor-wrapper hides the user's handler-fn from `(meta f)`, regardless of source-map config. The op behaves as designed; the documented graceful-fail is the expected response shape on every real call site today. The v0.2 workaround (opt-in `re-frame-pair.runtime/reg-event-db` macro, `rfp-rsg`) shipped briefly and was then retired by `rfp-hpu` once upstream re-frame `rf-ysy` started capturing the call-site directly — see *Source / call-site flattening* below.

### Native instrumentation primitives (rf-3p7 + rf-ybv consumers, 2026-04-26 / 27)

re-frame core grew first-class instrumentation hooks, letting runtime.cljs drop the hand-rolled correlation + dep-graph reverse-engineering it used to need. 10x's epoch buffer is kept as a fallback for older fixtures.

| Bead | Commit | Change |
|---|---|---|
| `rfp-fxv` | `18a98db` | Collapse onto rf-3p7's `:dispatch-id` correlation (replaces before-id/after-id walking) and `:input-query-vs` (replaces dep-graph reconstruction). Tag-schema contract honoured throughout. |
| `rfp-zl8` | `9d4e948` | New `native-epoch-buffer` + `native-trace-buffer` ring buffers fed by `register-epoch-cb` / `register-trace-cb` (rf-ybv). `coerce-native-epoch` translates `assemble-epochs` output to the §4.3a record shape. `epoch-by-id` / `last-epoch` / `last-claude-epoch` consult the native buffer first; legacy 10x path is fallback. |
| `rf1-jum` (Phase 2) | `4a575ac` | Prefer `day8.re-frame-10x.public` (rf1-jum upstream) over the inlined-rf walk: `read-10x-all-traces`, `read-10x-epochs`, `latest-epoch-id`, `epoch-count` all probe the public ns first. Legacy `inlined-rf-known-version-paths` walker remains for older 10x JARs. |

### New dispatch surfaces (2026-04-26 / 27)

| Bead | Commit | Change |
|---|---|---|
| `rfp-4ew` | `c87529c` | `--trace` path now goes through `re-frame.core/dispatch-and-settle` (rf-4mr): adaptive quiet-period over the `:fx [:dispatch ...]` cascade replaces the bash shim's fixed 80ms sleep. Promise can't round-trip cljs-eval, so the runtime stores the resolution in a session-local atom keyed by an opaque handle; `await-settle` polls. Settled record is reconstituted from the native epoch buffer (avoids `:keyword-fn name` stringification). Fallback to legacy `tagged-dispatch-sync!` + sleep when re-frame predates rf-4mr. |
| `rfp-zml` | `69c570d` | `dispatch-with --stub` (rf-ge8): per-dispatch fx-handler substitution via `:re-frame/fx-overrides` event-meta. `record-only-stub` builds the common `{fx-id (no-op-and-log)}` map for swapping out HTTP / navigation / local-storage at experiment-loop time; `stubbed-effects-since` exposes the captured-effect log. ops.clj parses repeatable `--stub :http-xhrio --stub :navigate`. SKILL.md's experiment-loop recipe gains a "side-effecting handlers" subsection naming `--stub` as the safe-iteration primitive. |

### Source / call-site flattening (2026-04-27)

Three separate upstream waves attached `:re-frame/source` (`{:file :line}`) at call sites — handler/sub registration, dispatch, subscribe. Meta strips on the `pr-str` / `cljs-eval` boundary back to bash, so each gets flattened onto the coerced epoch record.

| Bead | Commit | Change |
|---|---|---|
| `rfp-rsg` | `0520a44` | Opt-in `re-frame-pair.runtime/{reg-event-db,reg-event-fx,reg-sub,reg-fx}` macros captured `(meta &form)` and side-tabled to `handler-source-table`. **RETIRED** by `rfp-hpu`. |
| `rfp-hpu` | `fd74b8f` | Upstream `rf-ysy` made `re-frame.core/reg-*` defmacros that capture `*file*` + `(:line (meta &form))` and attach `{:file :line}` to the registered value via `with-meta` — interceptor chains for `:event` (vectors carry meta natively), fns for `:sub` / `:fx` (with-meta on fns works in CLJ via MetaFn and CLJS via IObj on cljs.core/fn). The local rfp-rsg side-table is now redundant; `runtime.clj` deleted, `handler-source` simplified to a single `(meta stored)` read. |
| `rfp-5zh` | `1fd8505` | `coerce-epoch` and `coerce-native-epoch` each gain `:event/source` from `(some-> event-vec meta :re-frame/source)` (rf-hsl consumer). Same shape on both paths. SKILL.md gains a *Why did this event fire?* recipe pairing `:event/source` (dispatch site) with `handler/source` (defn site) — one epoch read covers both. |
| `rfp-283` | `1038fcf` | Each `:subs/ran` entry on coerced epochs gains `:subscribe/source` (outer subscribe call site — the view that asked for the reaction) and `:input-query-sources` (vec parallel to `:input-query-vs`, source map for each input dep) (rf-cna consumer). Nil for bare-fn subscribes or pre-rf-cna re-frame. |

### Phase 2 `re-frame-debux` integration (2026-04-26 / 27)

Shipped — was previously deferred. `re-frame-debux` (Tier 2: `rfd-8g9`, `rfd-btn`) ran in parallel.

| Bead | Commit | Change |
|---|---|---|
| `rfp-mkf` | `ff6dbf2` | Fixture wired into `day8.re-frame/tracing` via `:local/root` (`../../../re-frame-debux`). `:counter/inc` wrapped with `fn-traced` as a worked example — the SKILL.md recipe finally has a live call site emitting non-nil `:debux/code`. |
| `rfp-6z2` | `a285c1d` | SKILL.md *Trace a handler/sub/fx form-by-form* recipe branches: PREFERRED (3 steps via `wrap-handler!` / `unwrap-handler!` from rfd-8g9), FALLBACK (5-step manual `fn-traced` rewrite, kept verbatim for older debux). `debux-runtime-api?` predicate detects which surface is loaded. |
| `ci-hpg` Phase 2 | `c498dc7` | `debux-runtime-api?` switched to probe upstream's dedicated `day8.re-frame.tracing.runtime/runtime-api?` var (re-frame-debux commit `6b04e6b`) — durable feature-detection contract owned by the library. |
| `rfp-12p` | `c81234a` | New SKILL.md recipe: *Trace a single expression at the REPL* (rfd-btn's `dbg` macro). Lower-friction alternative to handler-level `fn-traced` when the agent only wants to instrument one form. `dbg-macro-available?` probe mirrors `debux-runtime-api?`'s shape. `docs/inspirations-debux.md` §3.0 also gains a "Single-expression variant" paragraph. |

### `rfp-3es` — SKILL.md cardinal rule names a real op (2026-04-27)

Line 42's cardinal rule was telling the LLM to call `hot-reload/wait` — a label that exists only in comments and the historical `docs/initial-spec.md`. Replaced with the real surface: `scripts/tail-build.sh --probe '<form>'`, with a pointer to the *Hot-reload coordination* section. Prevents an LLM following the cardinal rule from attempting a non-existent invocation on the very first source edit.

---

## Next actions

### Near-term (next tag)

1. **Tag the next release** — `v0.1.0-beta.2` is on `main` un-tagged but the working tree is now ~30 commits past beta.2 (native epoch path, dispatch-and-settle, dispatch-with --stub, `:event/source`, `:subscribe/source`, Phase 2 debux integration). The natural next tag is `v0.1.0-beta.3` (or v0.2 if the new dispatch surfaces warrant a minor bump). Operator decision; CI green throughout.
2. **Phase 5 live verification** — exercise the edit-then-reload cycle end-to-end (`Edit` a fixture handler, `tail-build.sh --probe`, dispatch, observe). The probe protocol is coded and unit-tested, but no real edit→reload cycle has been run. `rfp-3es` confirmed the SKILL.md cardinal rule names the real op.
3. **Real-world day8 app exercise** — point re-frame-pair at an actual day8 re-frame application (not just the fixture) and run the SKILL.md recipes. Catch anything the fixture's narrow surface doesn't cover.
4. **Babashka-side unit tests — expand coverage.** `tests/ops_smoke.bb` now lands 20 deftests / 36 assertions (gated in CI via `npm run test:ops`). Still missing: bencode encode/decode roundtrip (property-test via `test.check`), `parse-predicate-args` flag-combination matrix, `read-port`'s candidate-cascade.
5. **CI: bash-shim E2E** — current smoke job only validates shebangs and `unknown-subcommand` parsing. Add a job that boots the fixture (or a mock nREPL listener), runs `discover-app.sh` + `dispatch.sh --trace`, and asserts edn shape. Catches regressions the unit tests can't.
6. **SKILL.md doc completeness** — surface `registrar-handler-ref` (used in experiment-loop recipe but undocumented), `health`/`version-report` (called by discover but unlisted), `app-db/schema`, and the new `--stub` flag wiring in the ops tables. Add recipes for the new `:event/source` / `:subscribe/source` fields ("Where was this dispatched?" / "Which view subscribed?"). Tracked in `rfp-q2q` (recipes) and `rfp-8yu` (ops tables).

### Shipped from prior backlog

- ~~Phase 2 `re-frame-debux` integration~~ — **shipped** (`rfp-6z2` / `a285c1d`). SKILL.md recipe branches on `debux-runtime-api?` and prefers `wrap-handler!` / `unwrap-handler!` (rfd-8g9) when available; legacy `fn-traced` rewrite kept as fallback. `ci-hpg` Phase 2 (`c498dc7`) then switched the predicate to upstream's dedicated `runtime-api?` var.
- ~~`rfp-rsg` reg-event-db macro for `handler/source`~~ — **shipped then RETIRED** (`rfp-rsg` / `0520a44`, then `rfp-hpu` / `fd74b8f`). Upstream `rf-ysy` made `re-frame.core/reg-*` defmacros that capture `*file*` + `(:line (meta &form))` and attach `{:file :line}` via `with-meta`. Local side-table is now redundant; `runtime.clj` deleted.

### v0.2 / deferred backlog

7. **Headless Playwright E2E rig** — ~6 weeks of work; replaces operator-driven fixture validation with automated browser-driven test. Right tool for full release-gate confidence.
8. **npm OIDC trusted publisher + `--provenance`** — release.yml currently uses a bare `NODE_AUTH_TOKEN` with no signing. Switch to OIDC (https://docs.npmjs.com/generating-authentication-tokens) and add `--provenance` for verifiable supply-chain.
9. **Watch streaming-via-`:out` transport** — currently pull-mode at 100ms. Streaming would reduce round-trips for long-running watches; spec §4.4 sketches the mechanism but reachability questions remain.
10. **Prune `docs/initial-spec.md`** to a design-archive (sections 1–3 + 7–8 only) — §4 ops definitions are now superseded by SKILL.md and runtime.cljs source.
11. **Move `tests/fixture/public/css/`** to CDN-link or generate at build time — 8.4k lines of bootstrap.css in the repo bloats checkout. Tradeoff: loses offline / air-gapped dev story.
12. **Inline-rf version path** — `inlined-rf-version-paths` is now an enumeration fallback (rfp-czf G6) but still hard-codes a known list. Probe `js/goog.global.day8.re_frame_10x.inlined_deps.re_frame` keys at runtime as the canonical source. Less load-bearing now that `day8.re-frame-10x.public` (rf1-jum) is preferred where loaded.
13. **Drop legacy 10x-buffer + sleep paths** — once every supported re-frame ships rf-ybv (`register-epoch-cb`) and rf-4mr (`dispatch-and-settle`), `read-10x-epochs` and the `tagged-dispatch-sync!` + `Thread/sleep` + `collect-after-dispatch` fallback can both go.

### Tracking

City-level umbrella bead `ci-8rn` ("drive re-frame-pair to v0.1.0-beta.1") closed 2026-04-26 — beta.1 + beta.2 squash-merged to `main`, all §8a unknowns resolved. Open work beads on the rig as of this refresh: doc-refresh chain (`rfp-jv3` STATUS, `rfp-q2q` SKILL recipes, `rfp-4x8` README, `rfp-1cj` inspirations + upstream-instrumentation, `rfp-kx0` companion-* banners, `rfp-6c6` initial-spec, `rfp-2oj` handler-source.sh docstring, `rfp-nvs` TESTING, `rfp-eft` README beta-1 label, `rfp-8yu` registrar/schema in SKILL ops tables) plus a small test-coverage backlog (`rfp-5g6` chunked-inject tests, `rfp-1p7` forensic/hot-path ops tests, `rfp-u9z` legacy 10x sub-runs inconsistency, `rfp-bqa` epoch-by-id fallback bug). Cross-rig follow-ups: upstream `rfd-8g9` shipped (commits `4ed07c9` + `6b04e6b`); `rfd-iqz` (toolchain modernisation) and `rfd-2nd` (CD dry-run) remain.
