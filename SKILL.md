---
name: re-frame-pair
description: >
  Pair-program with a live re-frame application. Attach to a running
  shadow-cljs build via nREPL, inspect app-db, dispatch events,
  hot-swap handlers, trace the six dominoes, and read re-frame-10x's
  epoch buffer — without touching source files unnecessarily. Use
  this skill whenever the user asks about their running re-frame app
  or uses any of: re-frame, app-db, dispatch, subscribe, reg-event,
  reg-sub, reg-fx, epoch, interceptor, re-frame-10x, re-com, shadow-cljs.
allowed-tools:
  - Bash(scripts/discover-app.sh *)
  - Bash(scripts/eval-cljs.sh *)
  - Bash(scripts/inject-runtime.sh *)
  - Bash(scripts/dispatch.sh *)
  - Bash(scripts/trace-window.sh *)
  - Bash(scripts/watch-epochs.sh *)
  - Bash(scripts/tail-build.sh *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame-pair

You are pair-programming with a developer on a **live, running re-frame application**. The app is running in a browser tab behind `shadow-cljs watch`. Your job is to help the developer understand, debug, and modify the app by *operating on the live runtime* — not just by reading source files.

Your agency runs through two coupled primitives:

1. **The REPL** — a shadow-cljs nREPL session connected to the browser runtime, where ClojureScript forms evaluate against the real app.
2. **re-frame-10x's epoch buffer** — the trace of every event (one *epoch* per dispatch) already collected by 10x. Read from it; never register a second trace callback.

Every operation below eventually becomes a short ClojureScript form evaluated through the REPL, usually against a helper function in the `re-frame-pair.runtime` namespace that the skill injects on connect.

---

## Cardinal rule — two modes of changing the app

- **REPL changes** (hot-swap a handler, evaluate a form, reset `app-db`) are **ephemeral**. They survive hot-reloads of unaffected namespaces, but are lost on full page reload. Use them for **probes, experiments, and throwaway fixes**.
- **Source edits** (using `Edit` / `Write`) are **permanent**. After any source edit, you *must* call `hot-reload/wait` before dispatching or tracing. Otherwise you'll interact with the pre-reload code and get misleading results.

Know which mode you're in and why.

---

## Connect first, every session

Before any other op, run:

```
scripts/discover-app.sh
scripts/app-summary.sh    # versions + registrar + live subs + app-db shape + health, in one round-trip
```

`discover-app.sh` locates the shadow-cljs nREPL port, connects, switches the session to `:cljs` mode for the running build, verifies re-frame + re-frame-10x + re-com + `trace-enabled?`, and injects the runtime namespace.

`app-summary.sh` is the recommended second call: a bootstrap bundle that lets you ground the conversation in 'what handlers exist, what subs are live, what's in app-db' without 5+ separate ops.

If any precondition fails, the script returns a structured error like `{:ok? false :missing :re-frame-10x}`. Report the failing check to the user verbatim; do *not* guess at workarounds.

Every op auto-reinjects the runtime namespace if a browser refresh dropped it; the response carries `:reinjected? true` when this happens — informational, not an error.

---

## Operations (the vocabulary)

Each op below is a short `scripts/eval-cljs.sh` invocation wrapping a call into `re-frame-pair.runtime`, or a dedicated script when the concern is broader than one form. Prefer the **structured ops** over `repl/eval` whenever a structured op fits.

### Read

| Op | Invocation | Returns |
|---|---|---|
| `app/summary` | `scripts/app-summary.sh` | Bootstrap bundle: versions, registrar inventory, live subs, app-db top-level keys + one-level shape, health. Use this as the first call after `discover-app.sh`. |
| `app-db/snapshot` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/snapshot)'` | Current `@app-db` |
| `app-db/get` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/app-db-at [:path :to :value])'` | Path-scoped value |
| `app-db/schema` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/schema)'` | Opt-in schema or nil |
| `registrar/list` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/registrar-list :event)'` | Ids under kind `:event`/`:sub`/`:fx`/`:cofx` |
| `registrar/describe` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/registrar-describe :event :cart/apply-coupon)'` | Kind + interceptor ids (events only) |
| `subs/live` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/subs-live)'` | Currently-subscribed query vectors |
| `subs/sample` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/subs-sample [:cart/total])'` | One-shot deref |

### Write

| Op | Invocation | Notes |
|---|---|---|
| `dispatch` | `scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]'` | Queued by default; `--sync` forces `dispatch-sync` |
| `reg-event` / `reg-sub` / `reg-fx` | `scripts/eval-cljs.sh '<full reg-* form>'` | Evaluates the registration form; hot-swap happens immediately. Ephemeral. |
| `app-db/reset` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/app-db-reset! ...)'` | Logged explicitly so the user sees it. Use sparingly. |
| `repl/eval` | `scripts/eval-cljs.sh '<arbitrary form>'` | Escape hatch. Prefer structured ops first. |

### Trace (read-only from 10x's epoch buffer)

| Op | Invocation | Returns |
|---|---|---|
| `trace/last-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-epoch)'` | Most recent epoch (any origin) |
| `trace/last-claude-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-claude-epoch)'` | Most recent epoch this session dispatched |
| `trace/epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/epoch-by-id "<id>")'` | Named epoch |
| `trace/dispatch-and-collect` | `scripts/dispatch.sh --trace '[:foo ...]'` | Fire + wait a frame + return the epoch by id |
| `trace/recent` | `scripts/trace-window.sh <ms>` | Epochs added in last N ms (pull) |
| `trace/find-where` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-where <pred>)'` | Most recent epoch matching a predicate — primary forensic op for "when did X happen?" post-mortems |
| `trace/find-all-where` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-all-where <pred>)'` | Every matching epoch, newest first — for trajectories rather than single transitions |

### Console / errors

A ring buffer of `js/console.{log,warn,error,info,debug}` calls captured by the runtime, tagged with `:who` so you can ask "what did MY dispatch log, vs the user's app, vs which handler threw?". Installed by `health` (idempotent, max 500 entries).

**`:who` values.** `:claude` for entries during your `tagged-dispatch-sync!` (sync only — async-queued handlers tag `:app`); `:app` for everything else; `:handler-error` synthesised from a `tagged-dispatch-sync!` catch with the throwable's stack.

| Op | Invocation | Returns |
|---|---|---|
| `console/tail` | `scripts/console-tail.sh` | All buffered entries newest-last |
| `console/tail-since` | `scripts/console-tail.sh --since-id 42` | Entries with `:id >= 42` (use `:next-id` from previous call to tail incrementally) |
| `console/tail-claude` | `scripts/console-tail.sh --who claude` | Only entries tagged `:claude` |
| `console/tail-handler-errors` | `scripts/console-tail.sh --who handler-error` | Synthesised entries from `tagged-dispatch-sync!`'s handler-throw catch |

### DOM ↔ source bridge (re-com `:src`)

When a re-com component is called with `:src (at)`, re-com attaches `data-rc-src="file:line"` to the rendered element — a two-way bridge between live DOM and the source line that produced it. Use it whenever the conversation is about a visible element.

**Prerequisites** — both must hold for a specific element's `:src` to resolve:
- re-com's debug instrumentation enabled in the dev build (a config flag in `re-com.config`)
- the component's call site passed `:src (at)`

**Degradation is per-element, not app-wide.** An app with `:src (at)` on most components but not all works fine — the bridge resolves where annotations are present and returns `{:src nil :reason :no-src-at-this-element}` for the few that aren't. When re-com debug is *entirely* off, every element returns `{:src nil :reason :re-com-debug-disabled}`. Tell the user which case they're hitting when it happens.

**Parsing the raw attribute.** You normally don't need to — structured ops (`dom/source-at`, `dom/describe`) and epoch render entries return `:src` as a pre-parsed map `{:file ... :line ...}`. The raw attribute is `"file:line"` (e.g. `"app/cart/view.cljs:84"`).

| Op | Invocation | Returns |
|---|---|---|
| `dom/source-at` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-source-at "#save-button")'` or `'(... :last-clicked)'` | `{:file :line}` for a CSS selector, or for the most recently clicked element |
| `dom/find-by-src` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-find-by-src "view.cljs" 84)'` | Live DOM elements rendered by that source line |
| `dom/fire-click-at-src` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-fire-click "view.cljs" 84)'` | Synthesise a click on the element rendered by that line — lets you exercise a specific call site by its source location, not a CSS path |
| `dom/describe` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-describe "#save-button")'` | Attrs + `data-rc-src` + attached listeners |

### Live watch (push-mode)

| Op | Invocation | Behaviour |
|---|---|---|
| `watch/window` | `scripts/watch-epochs.sh --window-ms 30000 --event-id-prefix :checkout/` | Runs for N ms, reports every matching epoch, summarises at end |
| `watch/count` | `scripts/watch-epochs.sh --count 5` | Runs until N epochs match |
| `watch/stream` | `scripts/watch-epochs.sh --stream --event-id-prefix :cart/` | Streams until disconnect, idle-timeout, or `watch/stop` |
| `watch/stop` | `scripts/watch-epochs.sh --stop` | Terminates any active watch for this session |

Predicates (any combination): `--event-id`, `--event-id-prefix`, `--effects`, `--timing-ms '>100'`, `--touches-path`, `--sub-ran`, `--render`.

v1 transport is **pull-mode** — repeated short evals at ~100ms cadence. See `docs/initial-spec.md` §4.4 for why streaming-via-`:out` is deferred.

### Hot-reload coordination

After any source edit, before the next dispatch or trace:

```
scripts/tail-build.sh --wait-ms 5000 --probe '(some/probe-form)'
```

`--probe` is a CLJS form chosen to change when the edited code reloads (see *Hot-reload protocol* below). If you don't know a good probe, omit `--probe` and the script falls back to a 300ms timer; the result includes `:soft? true` so you know it's timer-based.

### Time-travel (adapter over 10x internals)

These ops dispatch into re-frame-10x's *inlined* re-frame instance — 10x has no stable public undo API, so the adapter reaches into 10x internals directly. The events live in `day8.re-frame-10x.navigation.epochs.events`; each navigation event triggers `::reset-current-epoch-app-db`, which is `(reset! userland.re-frame.db/app-db <pre-state>)` — that's the time-travel mechanism. The reset only fires when 10x's `:settings :app-db-follows-events?` is true (default true; users can toggle from 10x's Settings panel). When 10x isn't loaded, the ops fail with `{:reason :ten-x-missing}`.

| Op | Invocation | Purpose |
|---|---|---|
| `undo/step-back` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-step-back)'` | Rewind one epoch (`::previous`) |
| `undo/step-forward` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-step-forward)'` | Redo (`::next`) |
| `undo/most-recent` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-most-recent)'` | Jump to head (`::most-recent`) |
| `undo/to-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-to-epoch <id>)'` | Jump to specific epoch (`::load`) |
| `undo/replay` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-replay)'` | Re-fire the selected event (`::replay`) |
| `undo/status` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-status)'` | `:selected-epoch-id`, `:back-count`, `:forward-count`, `:total-epochs`, `:app-db-follows-events?` |

Caveat (tell the user): undo rewinds `app-db` only. Side effects that already fired (`:http-xhrio`, navigation, `:dispatch-later`) are *not* undone. Warn before an experiment that depends on clean state. If `:app-db-follows-events?` is false, navigation succeeds but the app-db doesn't move — the ops surface `:warning :app-db-follows-events?-disabled` so you can flag this.

---

## Hot-reload protocol

Editing source is legitimate and often correct. The protocol is strict:

1. Make the edit with `Edit` / `Write`.
2. Call `scripts/tail-build.sh` with a `--probe` that verifies the browser has the new code:
   - If you edited a `reg-*` handler, a good probe is to read the new handler's function from the registrar and compare against what you captured before the edit.
   - If you edited a view or helper, the probe is a short form that reads a value that depends on the edited code.
   - If no good probe is available, omit `--probe` and accept the soft/timer-based confirmation.
3. Only after the probe succeeds do you proceed to `dispatch`, `trace/*`, etc.
4. If the probe times out, treat that as a compile error in the user's code — read the tail output, report it to the user, do *not* retry dispatching.

---

## Recipes (named procedures the user may ask for)

When the user asks a matching question, run the procedure below rather than improvising.

### "What's in `app-db`?" / "What did the last event do?"

- Snapshot or get: `app-db/snapshot`, `app-db/get`, or for a diff, `trace/last-epoch` → `:app-db/diff`.

### "Why didn't my view update?"

1. Identify the sub the view reads (ask the user if it's not in the view file).
2. `trace/last-claude-epoch` or `trace/last-epoch` — find the recent dispatch that should have updated it.
3. Walk the Layer 2 → Layer 3 chain of that sub. For each layer, compare the pre- and post-epoch value.
4. Report the equality gate that held the value constant ("Layer 2 at `[:user/profile]` returned the same map both times, so Layer 3 at `[:user/display-name]` short-circuited").

### "Explain this dispatch"

Run `trace/dispatch-and-collect` (or read a recent epoch), then narrate the six dominoes:

- Event vector + interceptor chain (by id)
- Coeffects injected
- Effects returned (`:effects/fired` for a cascade tree)
- `app-db` diff (changed paths, not the full before/after)
- Subs that re-ran vs cache-hit
- Components that re-rendered, with `:src` file:line where available

Keep it short. One compact paragraph per domino.

### "Post-mortem — how did we get here?"

When the user is stuck in a broken state and can't describe how they got there.

Procedure:

1. Ask the user what's wrong in *observable* terms ("the save button is grey", "the dashboard is empty"). Resolve any UI references to source via `dom/source-at` if possible.
2. Identify the **app-db key(s) or sub(s)** that govern the observation. If the user can't, trace the recent render for the offending component and walk its sub inputs.
3. Use `trace/find-where` to pinpoint the epoch where the governing key last changed to its current (bad) value. Example:
   ```
   scripts/eval-cljs.sh '(re-frame-pair.runtime/find-where
                           (fn [e] (= :expired (get-in (:only-after (:app-db/diff e))
                                                       [:auth-state]))))'
   ```
4. Report that epoch as the culprit: its event vector, the diff, and (crucially) the `:effects/fired` cascade. Often the root-cause dispatch is a child of another event — follow `:epoch-id` links upstream via `trace/epoch <id>`.
5. If no single epoch is responsible — the state drifted over many events — use `find-all-where` to get the trajectory. Narrate the 3–5 most relevant transitions rather than all of them.
6. Propose a fix. Usually one of: a handler that shouldn't have fired, a handler that did fire but was wrong, or a missing guard.

**Retention caveat.** 10x's epoch buffer is a ring (bounded size). Events that happened "a long time ago" may have aged out — `epochs-since` will return `:id-aged-out? true` and `find-where` will silently not find the transition. If you suspect retention is the limit, say so to the user explicitly: *"I can see the last N events but the change you're describing happened before that."* Then propose reproducing the path from a known state.

### "What effects fired?"

Walk `:effects/fired` from the epoch as a tree. Follow `:epoch-id` links into child epochs for `:dispatch*` effects. Flag pending effects (`:dispatch-later` not yet fired, `:http-xhrio` still awaiting response) as "queued, not landed."

### "What caused this re-render?"

Given a component name or `:src`, find the latest epoch whose `:renders` includes it. Reverse from there: the sub inputs that invalidated its outputs, then the event that invalidated the sub inputs.

### "Where in the code does this come from?"

Call `dom/source-at` on the element (or on `:last-clicked`). Return `{:file :line}`. If `:src` is nil, report which prerequisite is missing (re-com debug off, or this specific call site wasn't passed `:src (at)`).

### "Understand this component" / "What is this thing?"

When the user points at a UI element (CSS selector, *"the thing I last clicked"*, or a description), chain:

1. `dom/source-at` — resolve to `{:file :line}`.
2. `Read` the source file at that line, with ~30 lines of context.
3. Narrate: what the component is, what props it takes, which event(s) its interactions dispatch, and (if you can see them nearby) which subscriptions it reads.
4. If `data-rc-src` isn't resolvable, fall back to `dom/describe` to report tag/class/listeners, and ask the user to point at the source instead.

### "Fire the button at file:line"

Use `dom/fire-click-at-src`. Report the resulting epoch. Useful when you want to exercise a specific call site by its source location rather than picking a CSS path — distinctive to re-frame-pair. Tell the user if `:src (at)` is missing on that call site.

### "What did this dispatch log?"

Ground console output to a specific dispatch by pairing `console/tail` with `trace/dispatch-and-collect`:

1. `scripts/console-tail.sh` once first to read the current `:next-id` — that's your watermark.
2. Fire the dispatch via `scripts/dispatch.sh --trace '[:foo ...]'`.
3. `scripts/console-tail.sh --since-id <watermark>` — every entry that landed during the dispatch. Filter by `--who claude` if the user's app also logs during the same window and you want only the agent-driven side.
4. `--who handler-error` surfaces the synthesised stack from `tagged-dispatch-sync!`'s catch when an event handler threw — pair with the structured `:reason :handler-threw` response.

### "Dead code scan"

`registrar/list :event` and `registrar/list :sub`. Then `trace/recent` with a large window (e.g. 60s) — or ask the user to exercise the app first. Report registered ids that never appeared. *Caveat the user that trace coverage is not exhaustive.*

### Experiment loop

Same starting `app-db`, same event, only the code changes — any difference in the resulting epoch is attributable to your edit. Use whenever you're unsure if a change has the intended effect. Prerequisites: see *Time-travel* — 10x loaded with `:app-db-follows-events?` enabled.

Canonical procedure:

1. `trace/dispatch-and-collect [:foo ...]` → observe baseline. Capture the epoch id.
2. `undo/status` to see what side effects since the epoch of interest can't be rewound (`:http-xhrio`, navigation, landed `:dispatch-later`); warn user.
3. `undo/step-back` or `undo/to-epoch <id>` → rewind `app-db`.
4. **Modify the part of the system you're iterating on.**
   - *Handlers / subs / fx:* `(rf/reg-event-db :foo ...)` / `(rf/reg-sub :bar ...)` / `(rf/reg-fx :baz ...)` via `repl/eval`. Registrar picks up the new definition immediately.
   - *Views / helpers (plain `defn`s):* redefine the var via `repl/eval` — e.g. `(defn my-view [] ...)` in the appropriate namespace. Subsequent Reagent re-renders pick up the new fn.
   - *Permanent change:* `Edit` the source file, then `scripts/tail-build.sh --probe '...'` to wait for the reload to land.
5. **Verify the patch took before re-dispatching.** `registrar/describe :event :foo` (for a handler) should now return a different form/hash than what you captured at step 1. If the patch didn't land, re-dispatching will silently test the old code.
6. `trace/dispatch-and-collect [:foo ...]` → observe the new behaviour.
7. Compare the two epochs. Repeat until satisfied.
8. If the change was REPL-only and the user wants to keep it, *commit via source edit* — REPL changes are lost on full page reload.

### "Narrate the next N events"

`watch/count N` with no filter. Report each epoch as a short paragraph (event id, key `:effects/fired`, `app-db` diff summary) as it fires.

### "Alert me on slow events"

`watch/stream --timing-ms '>100'`. Silent until a match; report with interceptor timing breakdown when one hits.

### "Watch for X while I interact"

`watch/stream --event-id-prefix :checkout/` (or other predicate). Narrate each match; summarise when idle.

---

## Error handling

Every script returns structured edn like `{:ok? false :reason ...}` rather than raising. Translate to plain English for the user and suggest the fix named in `:reason`.

Common cases:

- `:nrepl-port-not-found` → tell the user to start their dev build with `shadow-cljs watch <build>`.
- `:browser-runtime-not-attached` → tell the user to open the app in a browser tab.
- `:trace-enabled-false` → the build wasn't compiled with `re-frame.trace.trace-enabled?` = true; this is a 10x requirement.
- `:ns-not-loaded` + namespace → re-frame-10x / re-com not loaded; check their deps.
- `:version-too-old` → report dep + observed vs required version.
- `:handler-error` inside an epoch → the user's handler threw; point at `:handler/error :stack`.
- `:timed-out? true` on a dispatch-and-collect → the animation frame never fired (tab backgrounded, debugger paused); ask the user to bring the tab forward.
- `:connection :lost` → reconnect by calling `scripts/discover-app.sh` again.

---

## Style guidance

- **Read before you write.** Use `app-db/snapshot` or `trace/last-epoch` to ground a hypothesis before proposing a change.
- **Prefer structured ops over `repl/eval`.** The escape hatch is available; use it for probes that don't fit the catalogue.
- **Keep it in re-frame's vocabulary.** Dispatch, reg-event-db, reg-sub — speak the same language the app speaks. Avoid `reset! app-db` except when surgically needed, and say so when you do.
- **Experiment, don't speculate.** When an answer isn't obvious, probe at the REPL against live data.
- **Validate before proposing.** When a hot-swap or suggestion is on the table, compose the form and run it against current state first.
- **Narrow detail as you go.** Summaries first; drill into a specific epoch, diff, or sub when the user asks.
- **Always resolve UI references to `:src` first.** When the user mentions a UI element, run `dom/source-at` (or read `:src` from the relevant epoch's `:renders`) before speculating about behaviour. `re-com/button at app/cart/view.cljs:84` grounds the conversation; *"the Save button somewhere in the profile view"* doesn't.
- **Surface undo limits.** Before any time-travel experiment, call `undo/status` and tell the user which side effects the undo cannot reverse.
