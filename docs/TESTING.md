# Testing plan

Four surfaces need coverage at different fidelities. See `docs/initial-spec.md` §9 for the architectural split.

## 1. Runtime unit tests (`tests/runtime/`)

**Status: scaffold written, untested against a running CLJS toolchain.**

`runtime_test.cljs` covers pure fns in `scripts/runtime.cljs` — the re-com classifier, `:src` parser, predicate matcher, session-sentinel shape, time-travel stubs. These can run via `shadow-cljs compile test` + `node out/test.js` without a browser or live re-frame app.

**To run (once set up):**

```bash
shadow-cljs compile test
node out/test.js
```

Failing points to flag on first run:

- `re-com-category` uses heuristic regexes over component names. If re-com's current namespace scheme differs, update the regexes.
- `parse-rc-src` assumes a `"file:line"` or `"file:line:column"` format for `data-rc-src`. Real re-com attribute format needs verification.

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

## What's explicitly **not** tested yet

- Connection against a real shadow-cljs build with nREPL enabled
- 10x epoch-buffer extraction — specific internal accessor needs spike
- Live-watch transport — `:out` streaming vs pull-mode decision pending
- Hot-reload probe-form selection heuristics

These are the §8a spike deliverables. Until the spike resolves them, the code paths exist in shape but are not exercised against reality.
