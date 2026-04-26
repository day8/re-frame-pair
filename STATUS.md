# Implementation status

A living record of what's actually implemented, what's scaffolded, and what's blocked on the spike. Updated per release. See `docs/initial-spec.md` for the design this is measured against.

**Last updated:** 2026-04-26 (post-beta.2 merge)

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
| `tests/runtime/` unit tests | **16 deftests / 141 assertions / 0 failures**; shadow-cljs `:node-test` build, run via `npm test`, gated in CI |
| `tests/fixture/` sample app | Built — minimal re-frame + 10x + re-com app; bundled bootstrap + re-com CSS for self-contained rendering |
| End-to-end against a live re-frame app | **Verified** — full §4.3a epoch shape (event, diff, effects, coeffects, interceptor-chain, subs/ran, subs/cache-hit, renders, timing) produced for UI clicks; all 5 predicate filters validated; time-travel rolls userland app-db correctly. v0.1.0-beta.1 + beta.2 squash-merged to `main` (PRs #1, #2). |

v0.1.0-beta.2 is on `main`. All §8a ground-truth unknowns are resolved (see *Spike findings* below), the runtime + fixture are validated end-to-end, and CI is green on both PRs. Remaining gate to tag: operator decision.

---

## Per-phase status (against `docs/initial-spec.md` §6)

| Phase | Deliverable | State | Notes |
|---|---|---|---|
| 0 | `eval-cljs.sh` round-trips a form | **Verified** | Round-trip confirmed against the live fixture; `(re-frame-pair.runtime/snapshot)` returns the full app-db. |
| 1 | Read surface (§4.1) | **Verified** | `snapshot`, `app-db-at`, `schema`, `registrar-list`, `registrar-describe`, `subs-live`, `subs-sample` all callable against the fixture. `registrar-describe` post-fix returns full `:by-kind` map (rfp-l7m A); `subs-live` correctly partitions ran-vs-cache-hit subs after a UI click. |
| 2 | Dispatch + trace (§4.2–§4.3) | **Verified** | `dispatch.sh --trace` returns the user-fired event's epoch with all §4.3a fields populated (rfp-fgm + rfp-l7m C); chained `:dispatch` effects no longer corrupt the returned epoch; `:claude-epoch-count` increments correctly on tagged dispatches. |
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
- 10x's epoch-store path and shape, plus its navigation event surface.
- re-com's `:src` format and debug gate (`re-com.config/debug?` = `^boolean js/goog.DEBUG`).

**Unit-tested (`tests/runtime/runtime_test.cljs` + `tests/runtime/fixtures.cljs`, runs via `npm test`):**

- 16 deftests / 141 assertions / 0 failures.
- `re-com?` / `re-com-category` (broadened heuristics).
- `parse-rc-src` (file:line shape, malformed cases, edge cases).
- `extract-query-vs` (cache-key map → query-v, duplicates, malformed entries).
- `epoch-matches?` predicate matrix (each flag in isolation, sparse epoch fields, falsy vs nil semantics).
- `coerce-epoch` shape against synthetic 10x match records (including orphaned render-burst).
- `version-below?` semver comparison (mismatched part counts, alpha tags, nil floor/observed).
- `undo-*` ten-x-missing failure paths.
- Synthetic-match fixture helper extracted to `tests/runtime/fixtures.cljs` so 10x shape changes only update one place.

**Verified end-to-end against the live fixture (2026-04-25 / 26):**

- `cd tests/fixture && npm install && npx shadow-cljs watch app` compiles and serves at `:8280`; nREPL on `:8777`.
- `scripts/discover-app.sh` returns `{:ok? true ...}` with all 5 health checks green (`:re-com-debug?`, `:last-click-capture?`, `:ten-x-loaded?`, `:app-db-initialised?`, `:trace-enabled?`).
- `scripts/dispatch.sh --trace '[:counter/inc]'` returns the user-fired event's epoch with every §4.3a field populated.
- All 5 watch predicate filters validated against script-driven dispatches; G3 fix (empty `:touches-path '[]'`) and G5 fix (malformed `--timing-ms` rejection) confirmed.
- Time-travel: `undo-step-back` rolls app-db back exactly one step against the fixture, `undo-most-recent` restores; gated correctly on `:app-db-follows-events?`.
- UI-click epoch shape: `:renders` populated with 21 components annotated by re-com category; `:subs/ran` and `:subs/cache-hit` correctly partition recomputed-vs-unchanged subs.
- Auto-reinject: clearing the session sentinel triggers seamless re-shipping of `runtime.cljs` with `:reinjected? true` flag on the response (no operator action needed after a browser refresh).

---

## Next actions

### Near-term (between beta.2 and beta.3)

1. **Tag `v0.1.0-beta.2`** — operator decision; CI green, validation done.
2. **Phase 5 live verification** — exercise the edit-then-reload cycle end-to-end (`Edit` a fixture handler, `tail-build.sh --probe`, dispatch, observe). The probe protocol is coded and unit-tested, but no real edit→reload cycle has been run.
3. **Real-world day8 app exercise** — point re-frame-pair at an actual day8 re-frame application (not just the fixture) and run the SKILL.md recipes. Catch anything the fixture's narrow surface doesn't cover.
4. **Babashka-side unit tests** — `ops.clj` currently has zero tests despite being ~700 LOC of critical glue. Bencode encode/decode roundtrip (property-test via `test.check`), `parse-predicate-args` flag-combination matrix, `read-port`'s candidate-cascade. ~5 hours of work, high-value.
5. **CI: bash-shim E2E** — current smoke job only validates shebangs and `unknown-subcommand` parsing. Add a job that boots the fixture (or a mock nREPL listener), runs `discover-app.sh` + `dispatch.sh --trace`, and asserts edn shape. Catches regressions the unit tests can't.
6. **SKILL.md doc completeness** — surface `registrar-handler-ref` (used in experiment-loop recipe but undocumented) and `health`/`version-report` (called by discover but unlisted) in the ops tables. Flesh out `README.md` quickstart.

### v0.2 / deferred backlog

7. **Headless Playwright E2E rig** — ~6 weeks of work; replaces operator-driven fixture validation with automated browser-driven test. Right tool for full release-gate confidence.
8. **npm OIDC trusted publisher + `--provenance`** — release.yml currently uses a bare `NODE_AUTH_TOKEN` with no signing. Switch to OIDC (https://docs.npmjs.com/generating-authentication-tokens) and add `--provenance` for verifiable supply-chain.
9. **Watch streaming-via-`:out` transport** — currently pull-mode at 100ms. Streaming would reduce round-trips for long-running watches; spec §4.4 sketches the mechanism but reachability questions remain.
10. **Prune `docs/initial-spec.md`** to a design-archive (sections 1–3 + 7–8 only) — §4 ops definitions are now superseded by SKILL.md and runtime.cljs source.
11. **Move `tests/fixture/public/css/`** to CDN-link or generate at build time — 8.4k lines of bootstrap.css in the repo bloats checkout. Tradeoff: loses offline / air-gapped dev story.
12. **Inline-rf version path** — `inlined-rf-version-paths` is now an enumeration fallback (rfp-czf G6) but still hard-codes a known list. Probe `js/goog.global.day8.re_frame_10x.inlined_deps.re_frame` keys at runtime as the canonical source.

### Tracking

`ci-8rn` (the umbrella city-level bead "drive re-frame-pair to v0.1.0-beta.1") is the only open work bead. Closing it is gated on the beta.1 / beta.2 tag operator decision (item 1 above).
