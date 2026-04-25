# Testing plan

Four surfaces need coverage at different fidelities. See `docs/initial-spec.md` §9 for the architectural split.

## 1. Runtime unit tests (`tests/runtime/`)

**Status: wired to shadow-cljs `:node-test` build. CI runs them per push.**

`runtime_test.cljs` covers pure fns in `scripts/re_frame_pair/runtime.cljs` — the re-com classifier (broadened to current re-com layout), the `:src` parser (file:line shape per `re-com.debug.cljs`), the predicate matcher, the cache-key extractor (`extract-query-vs`), the session-sentinel shape, the `coerce-epoch` translation against a synthetic 10x match record, and the `undo-*` ten-x-missing failure paths. They run via shadow-cljs's `:node-test` target — no browser, no live re-frame app.

**To run:**

```bash
npm install
npm test       # shadow-cljs compile runtime-test && node out/runtime-test.js
```

CI runs the same target on every push (see `.github/workflows/ci.yml`'s `runtime-test` job).

## 2. Bash-shim integration (`tests/shim/`)

**Status: not yet written.**

End-to-end against the fixture app. For each shell script in `scripts/*.sh`, assert:

- exit code matches the documented contract
- stdout is parseable as edn
- structured result has expected keys

Recommended approach: [`bats`](https://bats-core.readthedocs.io/) or a simple bash test harness. One `.bats` file per script; the fixture is started/stopped per test suite (not per test).

## 3. End-to-end in-browser (`tests/e2e/`)

**Status: not yet written. Blocked on the spike (§8a).**

Drives a headless Chrome via [playwright](https://playwright.dev/) against the fixture. Exercises:

- `watch-epochs.sh` streaming (or falling back to pull-mode reliably)
- `tail-build.sh` probe-based confirmation after a live source edit
- `dom/source-at`, `dom/find-by-src`, `dom/fire-click-at-src` against a rendered `:src`-annotated re-com component
- Full page refresh → re-injection via session-sentinel miss

This is where uncertainty about 10x internals, re-com's `data-rc-src` format, and the live-watch transport gets flushed out. Each test is a concrete artefact of a verified claim.

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

`hot-reload/wait`'s probe-based confirmation (§4.5) is *safety-critical* — Claude uses it to gate dispatches after a source edit, and a false positive means Claude interacts with stale code. Yet the only way to genuinely exercise it requires a real browser + real shadow-cljs + real edit + real compile pipeline — i.e. the E2E surface, which runs nightly and on `main`, not per-push.

Mitigation until we can run E2E per-push:

- **Unit-test the probe-selection heuristics** in `scripts/runtime.cljs` (which probe to pick for a `reg-*` edit vs. a view edit vs. no-good-probe-available). Cheap; catches drift in the selection logic without needing a browser.
- **Soft-confirmation signalling**: when no probe is available, `hot-reload/wait` returns `:soft? true`; Claude is instructed in SKILL.md to surface this to the user rather than trust it as a hard landing confirmation.
- **Never force release on a broken probe path** — the release workflow currently does not gate on probe-reload E2E because that fixture isn't wired. Cut the first `v0.1.0-beta.1` only after an E2E run covering this path has passed.

## What's explicitly **not** tested yet

- Connection against a real shadow-cljs build with nREPL enabled
- 10x epoch-buffer extraction — specific internal accessor needs spike
- Live-watch transport — `:out` streaming vs pull-mode decision pending
- Hot-reload probe-form selection heuristics

These are the §8a spike deliverables. Until the spike resolves them, the code paths exist in shape but are not exercised against reality.
