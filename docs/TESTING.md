# Testing plan

Four surfaces need coverage at different fidelities. See `docs/initial-spec.md` §9 for the architectural split.

## 1. Runtime unit tests (`tests/runtime/`)

**Status: wired to shadow-cljs `:node-test` build. 73 deftests / 385 assertions / 0 failures. CI runs them per push.**

`runtime_test.cljs` covers pure fns in `scripts/re_frame_pair/runtime.cljs` — the re-com classifier (broadened to current re-com layout), the `:src` parser (file:line shape per `re-com.debug.cljs`), the predicate matcher (`epoch-matches?`), the cache-key extractor (`extract-query-vs`), the session-sentinel shape, both `coerce-epoch` and `coerce-native-epoch` translations against synthetic 10x match records / `assemble-epochs` output (including the `:debux/code` surface from the inner `:event/handler` trace's `:tags :code` (parity asserted across both coerce paths so the legacy 10x and rf-ybv native paths can't drift on the lookup target), `:event/source` flattening from event-vec meta, `:subscribe/source` + `:input-query-sources` flattening on `:subs/ran` entries, and `:subscribe/source` flattening on `:subs/cache-hit` entries), the native ring buffers (`native-epoch-buffer` / `native-trace-buffer` ingest + drain, `epoch-by-id` / `last-epoch` / `last-claude-epoch` prefer-native-then-fall-back-to-10x), `collect-cascade-from-buffer` parent-chain walk by `:dispatch-id` (plus legacy 10x `chained-dispatch-ids` parity — transitive descendants surface, not just direct children), `await-settle` state transitions plus the `dispatch-and-settle!` and `dispatch-with-stubs!` fallback paths for re-frame builds predating rf-4mr / rf-ge8, the fx-stubs log helpers (`record-only-stub` / `build-stub-overrides` / `validate-fx-ids` / `stubbed-effects-since` / `clear-stubbed-effects!`), `subs-ran-from-native-traces` query-v dedupe, `subs-cache-hit-from-native-traces` `:cached?` filtering, the `dbg-macro-available?` probe, `console-tail-since` id/who filters, `tagged-dispatch-sync!` success and handler-error paths (current-who restoration, synthesised error entries), `app-summary` shape including app-db one-level coercion, `handler-source` across kinds (sub / fx / event with chain-meta / `:no-source-meta` fallback / not-registered / empty-meta-map), `version-below?` semver comparison, and the `undo-*` ten-x-missing failure paths. Synthetic-match helpers live in `tests/runtime/fixtures.cljs` — one place to update when 10x's shape changes. They run via shadow-cljs's `:node-test` target — no browser, no live re-frame app.

**To run:**

```bash
npm install
npm test       # shadow-cljs compile runtime-test && node out/runtime-test.js
```

CI runs the same target on every push (see `.github/workflows/ci.yml`'s `runtime-test` job).

## 1b. Babashka-side smoke tests (`tests/ops_smoke.bb`)

**Status: wired. 26 deftests / 51 assertions / 0 failures. CI runs them per push via `npm run test:ops`.**

Closes the gap that `npm test` (CLJS-only) leaves around `scripts/ops.clj`. Two coverage axes:

1. **Load-path smoke** — `bb scripts/ops.clj` parses + dispatches without analysis-time errors. The `rfp-xhx` regression (a forward reference to `list-builds-on-port`) slipped past `npm test` for exactly this reason; this runner catches the same shape next time.
2. **Pure-helper coverage** — `list-builds-on-port` (set-vs-seq normalisation, `rfp-j2i`), `read-port-candidates`, `build-id-from-args` (both `--build=app` and `--build=:app` forms, `rfp-jfp`), via `with-redefs` over the nREPL / fs seams.

Bencode round-trip, `parse-predicate-args` flag matrix, and `read-port` candidate-cascade are tracked in STATUS.md *Near-term* item 4.

## 2. Bash-shim integration (`tests/shim/`)

**Status: not yet written.**

End-to-end against the fixture app. For each shell script in `scripts/*.sh`, assert:

- exit code matches the documented contract
- stdout is parseable as edn
- structured result has expected keys

Recommended approach: [`bats`](https://bats-core.readthedocs.io/) or a simple bash test harness. One `.bats` file per script; the fixture is started/stopped per test suite (not per test).

## 3. End-to-end in-browser (`tests/e2e/`)

**Status: Automated rig: not yet written; operator-driven validation against `tests/fixture/` covers most of the surface — see STATUS.md *Verified end-to-end against the live fixture* list. Remaining gap: real-edit-then-reload-then-probe cycle (STATUS.md *Near-term* item 2).** §8a spike resolved 2026-04-25 (epoch-buffer accessor identified, `data-rc-src` format pinned, transport choice settled — see STATUS.md *Spike findings*). Headless Playwright is tracked in STATUS.md *v0.2 / deferred backlog* item 9.

Drives a headless Chrome via [playwright](https://playwright.dev/) against the fixture. Exercises (status against operator-driven fixture validation per STATUS.md):

- ✓ verified-by-operator: `watch-epochs.sh` pull-mode + all 5 predicate filters (streaming-via-`:out` deferred to v0.2)
- □ pending: `tail-build.sh` probe-based confirmation after a live source edit — the only §3 recipe still operator-pending (STATUS.md *Near-term* item 2)
- ✓ verified-by-operator: `dom/source-at`, `dom/find-by-src`, `dom/fire-click-at-src` against rendered `:src`-annotated re-com components
- ✓ verified-by-operator: full page refresh → re-injection via session-sentinel miss (`:reinjected? true` flag on the response)

The §8a uncertainty about 10x internals, re-com's `data-rc-src` format, and the live-watch transport is already flushed out (spike + operator validation); what an automated rig adds is closing the edit→reload→probe gap and gating per-push instead of nightly. Each test will be a concrete artefact of a verified claim.

## 4. Skill-prompt regression (`tests/prompts/`)

**Status: not yet written.**

A fixture app plus a harness that feeds representative Claude conversations and asserts the set of `scripts/*` invocations (and optionally the shape of Claude's reply). This catches silent drift in the skill's description and recipes as Claude's behaviour changes.

Candidate prompts:

- "What's in `app-db` under `:user/profile`?" → should call `app-db/get`
- "Trace `[:cart/apply-coupon "SPRING25"]`" → should call `dispatch.sh --trace`
- "Why didn't the header update after `[:profile/save ...]`?" → should walk subs, compare pre/post epoch values
- "Iterate on the cart handler until expired coupons are rejected" → should use the experiment-loop recipe (dispatch-and-collect, undo, reg-event-db, repeat)

## CI gating

| Surface | Runs on |
|---|---|
| Runtime unit tests | every push |
| Bash-shim integration | every push (once fixture exists) |
| End-to-end in-browser | `main` + nightly |
| Prompt regression | `main` + nightly |

Release gates on all four passing.

### Known coverage gap — probe-based reload

`tail-build.sh --probe`'s probe-based confirmation (§4.5) is *safety-critical* — Claude uses it to gate dispatches after a source edit, and a false positive means Claude interacts with stale code. Yet the only way to genuinely exercise it requires a real browser + real shadow-cljs + real edit + real compile pipeline — i.e. the E2E surface, which runs nightly and on `main`, not per-push.

Mitigation until we can run E2E per-push:

- **Unit-test the probe-selection heuristics** in `scripts/runtime.cljs` (which probe to pick for a `reg-*` edit vs. a view edit vs. no-good-probe-available). Cheap; catches drift in the selection logic without needing a browser.
- **Soft-confirmation signalling**: when no probe is available, `tail-build.sh --probe` returns `:soft? true`; Claude is instructed in SKILL.md to surface this to the user rather than trust it as a hard landing confirmation.
- **Never force release on a broken probe path** — the release workflow currently does not gate on probe-reload E2E because that fixture isn't wired. Cut the first `v0.1.0-beta.1` only after an E2E run covering this path has passed.

## What's explicitly **not** tested yet

- **Real edit→reload→probe cycle.** `tail-build.sh`'s probe protocol is unit-tested for selection logic, but no automated test fires a real edit through shadow-cljs and observes the probe land. STATUS.md *Near-term* item 2.
- **Real-world day8 app exercise.** Fixture coverage is narrow by construction. STATUS.md *Near-term* item 3.
- **Bash-shim integration via `tests/shim/`** (§2 above). The `tests/ops_smoke.bb` runner covers the babashka dispatcher's load and pure helpers but not the shell wrappers' edn-shape contracts.
- **Headless E2E.** Browser-driven validation of `dom/*` ops, the watch transport, and full-refresh re-injection. STATUS.md *v0.2 / deferred backlog* item 9.

The §8a spike unknowns (10x epoch-buffer accessor, `data-rc-src` format, live-watch transport choice) are all resolved — see STATUS.md *Spike findings*. The runtime accessors are exercised by both unit tests (against synthetic match records) and operator-driven fixture validation; what's missing is automating the latter.
