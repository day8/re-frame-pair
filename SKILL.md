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

Your main advantage is not just that you can read `app-db`. Your main advantage is that you can close the debugging loop against the real runtime. Without re-frame-pair you would read source and guess what happened in the browser. With re-frame-pair you can ask the running app which event fired, what changed in `app-db`, which effects fired, which subscriptions re-ran or cache-hit, which views rendered, and where the relevant source lives.

Prefer this empirical loop:

1. Observe the current runtime state.
2. Inspect the relevant epoch.
3. Form a hypothesis.
4. Probe with a dispatch, app-db read, hot-swap, side-effect stub, or REPL eval.
5. Compare the new epoch with the baseline.
6. Only then edit source.

Use this especially when a UI did not update, an event fired but the visible result is wrong, `app-db` ended up in a bad state, the user cannot remember the exact path that caused a bug, you need to find the source behind a visible control, or you want to test a handler/sub/fx change before committing it.

## Quick Start

Three calls and you're inside the app:

```bash
scripts/discover-app.sh
scripts/app-summary.sh
scripts/dispatch.sh --trace '[:cart/apply-coupon "SPRING25"]'
```

`discover-app.sh` is mandatory at the start of each session. It finds the nREPL port, switches to CLJS mode, verifies preconditions, injects the runtime namespace, and returns startup context with app-db shape plus recent event pointers.

## Core Workflows

- **Why didn't the UI update?** Follow event -> `app-db` diff -> subscriptions ran/cache-hit -> renders -> DOM/source.
- **What happened after this dispatch?** Narrate the six dominoes: event, interceptors/coeffects, handler result, effects, subscriptions, renders.
- **Post-mortem: how did app-db get here?** Do not make the user reconstruct the reproduction path from memory. Search recent epochs for the transition that introduced the bad value, then follow parent/child dispatch ids if the cause was cascaded.
- **Can this fix work?** Hot-swap or eval the smallest runtime change, re-run the same event from the same state when possible, then compare epochs.
- **Where is that UI wired?** Resolve visible controls/components to `:src`, then inspect their dispatches, subscriptions, and handlers.
- **Can I iterate without real side effects?** Stub selected fx for one dispatch cascade, record what would have fired, and discard the stubs when the cascade settles.

## Load Detail On Demand

This file is the principal operating model. Before doing a detailed workflow, read the relevant supporting document:

| Task | Read |
|---|---|
| Operation names, command forms, epoch fields | `docs/skill/operations.md` |
| Post-mortem / bad app-db state | `docs/skill/post-mortems.md` |
| UI did not update, rendered output wrong, source for visible UI | `docs/skill/ui-debugging.md` |
| Runtime probing, hot-swap, time-travel, side-effect stubs | `docs/skill/probing-and-hot-swap.md` |
| Source metadata, macro opt-in, handler/source gaps | `docs/skill/source-meta.md` |
| Errors, setup failures, health checks | `docs/skill/troubleshooting.md` |

## Operating Principles

- **Read live state before guessing.** `app-db/snapshot`, `trace/last-epoch`, or `app-summary` first; hypothesis after.
- **Probe, don't speculate.** When an answer isn't obvious, evaluate against live data.
- **Prefer baseline/comparison evidence.** Capture the epoch before a probe, run the smallest useful change, then compare the resulting epoch instead of relying on intent.
- **REPL access is your second mode.** You can hot-swap a handler / sub / fx, redefine a `defn`, or `reset!` `app-db` directly through `repl/eval`. REPL changes are ephemeral; source edits are permanent.
- **After any source edit, wait for hot reload.** Run `scripts/tail-build.sh --probe '<form>'` before dispatching or tracing, otherwise you may interact with the pre-reload code.
- **Surface failures verbatim.** Every script returns structured edn. Translate `:reason` to plain English; do not paper over it.
- **Narrow detail as you go.** Summaries first; drill into a specific epoch / diff / sub when the user asks.
- **Always resolve UI references to `:src` where possible.** `re-com/button at app/cart/view.cljs:84` grounds the conversation.
- **Prefer dedicated shims over raw `eval-cljs`.** When a shim exists, it handles namespace names, quoting, and common flag combinations.

## Starter Pack

These cover most conversations:

| Op | Use it for |
|---|---|
| `scripts/app-summary.sh` | Extended bootstrap: versions, registrar, live subs, app-db shape, health |
| `scripts/dispatch.sh '[:foo ...]'` | Fire an event; add `--sync` for sync or `--trace` for full epoch + cascade |
| `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-epoch)'` | Most recent epoch, fully coerced |
| `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-where <pred>)'` | Forensic search for the most recent matching epoch |
| `scripts/handler-source.sh :event :foo/bar` | `{:file :line}` of a registered handler |
| `scripts/eval-cljs.sh '(re-frame-pair.runtime/subs-live)'` | Currently-cached query vectors |
| `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-source-at "#save-btn")'` | DOM element -> source line |

## When Not To Use

- The app is not running yet, so there is no live runtime to attach to.
- The code being debugged is not routed through re-frame.
- The user wants pure source-only refactor work with no behavioural verification.

## Epoch Summary

An epoch is everything that happened in response to one dispatch. It may include:

- `:event`, `:event/original`, `:event/source`
- `:dispatch-id`, `:parent-dispatch-id`
- `:app-db/diff`; full before/after snapshots are on demand only
- `:effects/fired`
- `:interceptor-chain`
- `:subs/ran`, `:subs/cache-hit`
- `:renders`
- `:debux/code` when re-frame-debux instrumentation is active
- `:coeffects`

Use compact diffs first. Ask for full app-db snapshots only when the compact diff is insufficient.
