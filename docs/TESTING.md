# Testing strategy

Four test surfaces at different fidelities. See [`docs/initial-spec.md`](initial-spec.md) §9 for the architectural split. Current counts and what's still pending live in [`STATUS.md`](../STATUS.md).

## 1. Runtime unit tests (`tests/runtime/`)

Pure-fn coverage for `scripts/re_frame_pair/runtime/*.cljs` (and the facade `runtime.cljs`). Runs via shadow-cljs's `:node-test` target — no browser, no live re-frame app. Synthetic-match helpers live in `tests/runtime/fixtures.cljs` so 10x shape changes only need to land in one place.

What this surface covers:

- The re-com classifier, the `:src` parser, the predicate matcher (`epoch-matches?`), the cache-key extractor (`extract-query-vs`).
- `coerce-epoch` and `coerce-native-epoch` translations against synthetic 10x match records and `assemble-epochs` output. Parity asserted across both paths so the legacy 10x and the rf-ybv native paths can't drift on field lookup.
- The native ring buffers (`native-epoch-buffer` / `native-trace-buffer` ingest + drain; prefer-native-then-fall-back-to-10x for `epoch-by-id` / `last-epoch` / `last-claude-epoch`).
- `collect-cascade-from-buffer` parent-chain walk by `:dispatch-id`, plus legacy 10x `chained-dispatch-ids` parity.
- `await-settle` state transitions, `dispatch-and-settle!` and `dispatch-with-stubs!` fallback paths for re-frame builds predating rf-4mr / rf-ge8.
- Fx-stubs log helpers (`record-only-stub`, `build-stub-overrides`, `validate-fx-ids`, `stubbed-effects-since`, `clear-stubbed-effects!`).
- Source-meta flattening on coerced epochs: `:event/source` from event-vec meta, `:subscribe/source` + `:input-query-sources` on `:subs/ran` and `:subs/cache-hit` entries, `:debux/code` from the inner `:event/handler` trace.
- `console-tail-since` filters, `tagged-dispatch-sync!` success and handler-error paths, `app-summary` shape, `handler-source` across kinds, `version-below?` semver comparison, `undo-*` ten-x-missing failure paths.

To run:

```bash
npm install
npm test       # shadow-cljs compile runtime-test && node out/runtime-test.js
```

CI runs the same target on every push.

## 1b. Babashka-side smoke tests (`tests/ops_smoke.bb`)

Closes the gap that `npm test` (CLJS-only) leaves around `scripts/ops.clj`. Two coverage axes:

1. **Load-path smoke** — `bb scripts/ops.clj` parses + dispatches without analysis-time errors. Catches forward-reference regressions that CLJS unit tests can't see.
2. **Pure-helper coverage** — `list-builds-on-port`, `read-port-candidates`, `build-id-from-args` (both `--build=app` and `--build=:app` forms), via `with-redefs` over the nREPL / fs seams.

Bencode round-trip property-test, `parse-predicate-args` flag matrix, and `read-port`'s candidate-cascade are still pending — see [`STATUS.md`](../STATUS.md) *Near-term*.

To run: `npm run test:ops`. CI runs it on every push.

## 1c. Skill / recipe contract tests (`tests/skill_recipe_smoke.bb`)

Pins SKILL.md and `docs/skill/debux.md` invariants — heading text, allowed-tools, recipe section presence, fixture surface contract. Catches silent drift between the skill prose and the runtime / fixture surface.

Pins the fixture's `dispatch.sh --stub` recipe contract: `events.cljs` keeps a real `:test/log-message` `reg-fx`, the direct + cascaded events that emit it, and the README smoke steps that verify the runtime stub log against the fixture's real effect log. CI guard for the fixture surface; doesn't replace the live browser smoke.

To run: `npm run test:skill-recipe`. CI runs it on every push.

## 2. Bash-shim integration (`tests/shim/`)

**Not yet written.** End-to-end against the fixture app. For each shell script in `scripts/*.sh`, assert:

- exit code matches the documented contract
- stdout is parseable as EDN
- structured result has the expected keys

Recommended approach: [`bats`](https://bats-core.readthedocs.io/) or a simple bash test harness. One `.bats` file per script; the fixture is started/stopped per test suite (not per test).

The fixture has a concrete `--stub` target available for this suite: `:test/log-message`. The smoke should clear `app.events/test-fx-log` and `re-frame-pair.runtime`'s stub log, run `scripts/dispatch.sh --trace --stub :test/log-message '[:test/log-then-dispatch "hello"]'`, then assert the stub log contains both root and child payloads while the real fixture log remains empty.

## 3. End-to-end in-browser (`tests/e2e/`)

**Automated rig not yet written.** Operator-driven validation against `tests/fixture/` covers most of the surface today (see [`STATUS.md`](../STATUS.md)).

A future Playwright rig drives a headless Chrome against the fixture and exercises:

- `watch-epochs.sh` pull-mode + all 5 predicate filters.
- `tail-build.sh` probe-based confirmation after a live source edit — the only recipe path still operator-pending.
- `dom/source-at`, `dom/find-by-src`, `dom/fire-click-at-src` against rendered `:src`-annotated re-com components.
- Full page refresh → re-injection via session-sentinel miss (`:reinjected? true` flag on the response).

What an automated rig adds: closing the edit→reload→probe gap and gating per-push instead of nightly. Tracked in [`STATUS.md`](../STATUS.md) *deferred*.

## 4. Skill-prompt regression (`tests/prompts/`)

**Not yet written.** A fixture app plus a harness that feeds representative Claude conversations and asserts the set of `scripts/*` invocations (and optionally the shape of Claude's reply). Catches silent drift in the skill's description and recipes as Claude's behaviour changes.

Candidate prompts:

- "What's in `app-db` under `:user/profile`?" → should call `app-db/get`.
- "Trace `[:cart/apply-coupon "SPRING25"]`" → should call `dispatch.sh --trace`.
- "Why didn't the header update after `[:profile/save ...]`?" → should walk subs, compare pre/post epoch values.
- "Iterate on the cart handler until expired coupons are rejected" → should use the experiment-loop recipe (dispatch, observe, undo, reg-event-db, repeat).

## CI gating

| Surface | Runs on |
|---|---|
| Runtime unit tests | every push |
| Babashka smoke tests | every push |
| Skill / recipe contract tests | every push |
| Bash-shim integration | every push (once written) |
| End-to-end in-browser | `main` + nightly (once written) |
| Prompt regression | `main` + nightly (once written) |

Release gates on all wired surfaces passing.

### Known coverage gap — probe-based reload

`tail-build.sh --probe`'s probe-based confirmation (§4.5) is *safety-critical* — Claude uses it to gate dispatches after a source edit, and a false positive means Claude interacts with stale code. Genuinely exercising it requires a real browser + real shadow-cljs + real edit + real compile pipeline — i.e. the E2E surface, which is not yet wired.

Mitigation until E2E lands per-push:

- **Unit-test the probe-selection heuristics** in `runtime.cljs` (which probe to pick for a `reg-*` edit vs. a view edit vs. no-good-probe-available). Cheap; catches drift in the selection logic without needing a browser.
- **Soft-confirmation signalling**: when no probe is available, `tail-build.sh --probe` returns `:soft? true`; SKILL.md instructs Claude to surface this to the user rather than trust it as a hard landing confirmation.
- **Never gate the release pipeline on a broken probe path** — the release workflow does not gate on probe-reload E2E because that fixture isn't wired yet.

## What's explicitly **not** tested yet

- **Real edit→reload→probe cycle.** Probe-selection logic is unit-tested; no automated test fires a real edit through shadow-cljs and observes the probe land.
- **Real-world day8 app exercise.** Fixture coverage is narrow by construction. Pointing rfp at a production day8 app catches what the fixture's surface doesn't cover.
- **Bash-shim integration via `tests/shim/`** (§2 above). `tests/ops_smoke.bb` covers the babashka dispatcher's load and pure helpers but not the shell wrappers' EDN-shape contracts.
- **Headless E2E.** Browser-driven validation of `dom/*` ops, the watch transport, and full-refresh re-injection.
