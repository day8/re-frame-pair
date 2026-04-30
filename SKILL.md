---
name: re-frame-pair
description: >
  Pair-program with a live re-frame application via shadow-cljs nREPL.
  Inspect app-db, dispatch events, hot-swap handlers, trace dispatches,
  read epoch data, time-travel, and perform post-mortems without source
  edits when probing. Use whenever the user asks about their running
  re-frame app or mentions: re-frame, app-db, dispatch, subscribe,
  reg-event, reg-sub, reg-fx, epoch, interceptor, re-frame-10x, re-com,
  shadow-cljs.
allowed-tools:
  - Bash(scripts/discover-app.sh *)
  - Bash(scripts/app-summary.sh *)
  - Bash(scripts/eval-cljs.sh *)
  - Bash(scripts/console-tail.sh *)
  - Bash(scripts/handler-source.sh *)
  - Bash(scripts/inject-runtime.sh *)
  - Bash(scripts/dispatch.sh *)
  - Bash(scripts/trace-recent.sh *)
  - Bash(scripts/watch-epochs.sh *)
  - Bash(scripts/tail-build.sh *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame-pair

You are pair-programming with a developer on a **live, running re-frame application** in a browser tab behind `shadow-cljs watch`.

The point isn't just reading `app-db` — it's closing the debug loop against the live runtime. Ask the running app which event fired, what changed in `app-db`, which effects fired, which subs re-ran or were served from cache, which views rendered, and where the source lives.

## The Empirical Loop

Prefer this loop over reading source and guessing:

1. Observe the current runtime state.
2. Inspect the relevant epoch.
3. Form a hypothesis.
4. Probe with a dispatch, app-db read, hot-swap, side-effect stub, or REPL eval.
5. Compare the new epoch with the baseline.
6. Only then edit source.

Reach for the loop especially when:

- A UI didn't update, or its visible result is wrong.
- `app-db` ended up in a bad state.
- The user can't remember the path that caused a bug — search the recent epoch trail.
- You need to find the source behind a visible control.
- You want to test a handler / sub / fx change before committing it.

### What is an epoch?

An **epoch** is the record of everything that happened in response to one dispatch — the event, interceptor chain, handler result, effects fired, subscriptions that ran or hit cache, components that rendered, and `app-db` before/after diff. The full field list lives in [`docs/skill/operations.md`](docs/skill/operations.md). Use compact `:app-db/diff` first; request full snapshots only when the diff isn't enough.

## When Not To Use

- The app isn't running yet, so there's no live runtime to attach to.
- The code being debugged isn't routed through re-frame.
- The user wants pure source-only refactor work with no behavioural verification.

## Quick Start

```bash
scripts/discover-app.sh
scripts/app-summary.sh
scripts/dispatch.sh --trace '[:cart/apply-coupon "SPRING25"]'
```

`discover-app.sh` is **mandatory** at the start of each session. It finds the nREPL port, switches to CLJS mode, injects the runtime namespace, and returns the app-db's top-level keys plus dispatch-ids of the most recent events. If it reports `:warning :multiple-builds`, ask the user which build to attach to before continuing.

Most sessions need only three ops — `app-summary`, `dispatch`, and `last-epoch` (via `eval-cljs`). For the full reference see [`docs/skill/operations.md`](docs/skill/operations.md).

## Detail On Demand

| Question / task | Read |
|---|---|
| Operation names, command forms, epoch fields, console tags | [`docs/skill/operations.md`](docs/skill/operations.md) |
| Post-mortem — how did `app-db` get here? Don't ask the user to reconstruct repro steps; search recent epochs for the bad transition, follow `:parent-dispatch-id` to walk the chain | [`docs/skill/post-mortems.md`](docs/skill/post-mortems.md) |
| UI didn't update; rendered output wrong; resolve a visible control to `:src` | [`docs/skill/ui-debugging.md`](docs/skill/ui-debugging.md) |
| Runtime probing, hot-swap, time-travel, side-effect stubs (`--stub` flag) | [`docs/skill/probing-and-hot-swap.md`](docs/skill/probing-and-hot-swap.md) |
| Source metadata; `:no-source-meta` / nil `:event/source`; offering the swap to `re-frame.core-instrumented` | [`docs/skill/source-meta.md`](docs/skill/source-meta.md) |
| Form-by-form trace inside a handler / sub / fx (re-frame-debux — an optional source-printing instrumentation library) | [`docs/skill/debux.md`](docs/skill/debux.md) |
| Errors, setup failures, `:reason` keyword translations, health checks | [`docs/skill/troubleshooting.md`](docs/skill/troubleshooting.md) |

## Operating Principles

- **Read live state before guessing.** `app-db/snapshot`, `trace/last-epoch`, or `app-summary` first; hypothesis after.
- **Compare epochs, don't rely on intent.** Capture the epoch, run the smallest change, compare.
- **REPL access is your second mode.** Hot-swap a handler / sub / fx, redefine a `defn`, or `reset!` `app-db`. REPL changes are ephemeral; source edits stick. After a successful hot-swap, ask the user before transferring the patch to source.
- **After any source edit, wait for hot reload.** Run `scripts/tail-build.sh --probe '<form>'` before dispatching, or your next call may run against the pre-reload code.
- **Surface failures verbatim.** Every script returns structured EDN. Translate `:reason` to plain English using [`docs/skill/troubleshooting.md`](docs/skill/troubleshooting.md). `:reinjected? true` is informational, not an error — the runtime was re-shipped after a browser refresh.
- **Narrow detail as you go.** Summaries first; drill in on request.
- **Resolve UI references to `:src`** — `re-com/button at app/cart/view.cljs:84` grounds the conversation.
- **Prefer dedicated shims over raw `eval-cljs`.** Shims handle namespacing, quoting, and flag combinations.
- **Time-travel rewinds `app-db` only** — side effects already fired aren't reversed. Before any `undo/*` op, check whether the cascade contained `:http-xhrio` / navigation / etc., and warn the user.
- **Requires `re-frame.trace.trace-enabled? true`.** If `discover-app.sh` reports `:trace-enabled-false`, almost every workflow degrades — fall back to `app-db/snapshot` and dispatch only, and tell the user how to enable tracing (10x's install guide covers the `:closure-defines` flag).
- **Offer the source-meta swap when relevant.** When you read source files and notice the host uses plain `re-frame.core` (or `re-frame.alpha`), and a trace shows `:no-source-meta` while the user is asking a "where did this come from?" question, offer to swap their `:require` to `re-frame.core-instrumented`. See [`docs/skill/source-meta.md`](docs/skill/source-meta.md).
- **Drill into elision markers; don't treat them as data loss.** Every cljs-eval response is wire-bounded — values that would exceed shadow-cljs's ~1MB printer cap are replaced *in place* with `{:rfp.wire/elided true :path [...] :cursor "<id>" :type ... :count ... :summary {...}}` markers. The runtime keeps the full value buffered; fetch a slice with `eval-cljs.sh '(re-frame-pair.runtime.wire/fetch-path "<cursor>" [<path>])'`. The bash response also surfaces top-level `:rfp.wire/cursor` and `:rfp.wire/elisions` when an elision happened, and `:rfp.wire/value-fits? false` flags it. Read the elision's `:summary` first (`{:type :map :count 84 :sample-keys [...]}`) — usually that answers the question without needing to drill at all.
