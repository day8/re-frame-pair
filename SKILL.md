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

**Multi-build setups.** If the connected nREPL has more than one shadow-cljs build active (e.g. `:app` and `:storybook` running side by side), `discover` adds `:warning :multiple-builds :picked <id> :others [...]` and writes a stderr line. Surface that to the user — the picked build may not be the one they meant. `scripts/discover-app.sh --list` lists every candidate port + its active builds without injecting, so you can pick deliberately via `--build=<id>` or `SHADOW_CLJS_BUILD_ID`. An explicit `--build=` or env var suppresses the warning (the operator made the choice).

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
| `handler/source` | `scripts/handler-source.sh :event :cart/apply-coupon` | `{:file :line :column}` of the handler. Reliable when registered via the opt-in `re-frame-pair.runtime/reg-event-db` etc. (`:source :registration-macro`); falls back to compiled-fn `(meta f)` (`:source :fn-meta`); returns `:no-source-meta` cleanly when neither path produces a hit. See "Where in the code is this handler?" recipe for the opt-in. |

### Write

| Op | Invocation | Notes |
|---|---|---|
| `dispatch` | `scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]'` | Queued by default; `--sync` forces `dispatch-sync`; `--stub :http-xhrio` (rf-ge8) substitutes a record-only stub for the named fx in the dispatch and its cascade |
| `reg-event` / `reg-sub` / `reg-fx` | `scripts/eval-cljs.sh '<full reg-* form>'` | Evaluates the registration form; hot-swap happens immediately. Ephemeral. |
| `app-db/reset` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/app-db-reset! ...)'` | Logged explicitly so the user sees it. Use sparingly. |
| `repl/eval` | `scripts/eval-cljs.sh '<arbitrary form>'` | Escape hatch. Prefer structured ops first. |

### Trace (read-only from 10x's epoch buffer)

| Op | Invocation | Returns |
|---|---|---|
| `trace/last-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-epoch)'` | Most recent epoch (any origin) |
| `trace/last-claude-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-claude-epoch)'` | Most recent epoch this session dispatched |
| `trace/epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/epoch-by-id "<id>")'` | Named epoch |
| `trace/dispatch-and-settle` | `scripts/dispatch.sh --trace '[:foo ...]'` | Fire + await the cascade (adaptive quiet-period) + return root and cascaded epochs |
| `trace/recent` | `scripts/trace-window.sh <ms>` | Epochs added in last N ms (pull) |
| `trace/find-where` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-where <pred>)'` | Most recent epoch matching a predicate — primary forensic op for "when did X happen?" post-mortems |
| `trace/find-all-where` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-all-where <pred>)'` | Every matching epoch, newest first — for trajectories rather than single transitions |

Every coerced epoch also carries `:debux/code` — a vec of `{:form :result :indent-level :syntax-order :num-seen}` per-form trace entries written by [re-frame-debux](https://github.com/day8/re-frame-debux) when a handler / sub / fx has been wrapped with `day8.re-frame.tracing/fn-traced`, or when individual forms have been wrapped with `day8.re-frame.tracing/dbg` (rfd-btn). `nil` when debux isn't on the classpath or no wrapping is in place; a vec (possibly empty) when it is. See *"Trace a handler / sub / fx form-by-form"* and *"Trace a single expression at the REPL"* below for the two on-demand procedures.

Every coerced epoch also carries `:event/source` — `{:file :line}` of the dispatch call site. Populated when the event was dispatched via `re-frame.macros/dispatch` or `re-frame.macros/dispatch-sync` (rf-hsl), which capture `*file*` + `(:line (meta &form))` at macro-expansion time and attach them to the event vector as `:re-frame/source` meta. `nil` for events dispatched via the bare `re-frame.core/dispatch` fn or on a re-frame predating those macros. Pair with `handler/source` for "why did this fire / where is the handler defined" — see *"Why did this event fire?"* below.

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

### "Is the rendered output correct?" / "Verify the view matches the data"

When the user asks whether a panel is showing the right values, don't stop at the data-flow check — verify what's actually in the DOM. Strong-form: prove data → sub → render → DOM, not just data → sub.

1. Read the panel's data inputs (`app-db/get`, relevant sub outputs).
2. Compute the expected rendered text from those inputs (e.g. `(str "items: " (count items))`).
3. Use `dom/find-by-src "<view-file>.cljs" <line>` for a specific element, or a broader query when you don't know the line:
   ```
   eval-cljs '(->> (.. js/document (querySelectorAll "[data-rc-src]"))
                   array-seq
                   (filter #(re-find #"<pattern>" (.-textContent %)))
                   (mapv #(hash-map :src (.getAttribute % "data-rc-src") :text (.-textContent %))))'
   ```
4. Compare rendered vs expected. If they differ, data was right but Reagent didn't re-render — chase sub invalidation or `:dev/after-load`. If both match, the panel is correct end-to-end.

### "Trace a handler / sub / fx form-by-form"

When the user wants to see what each *expression inside* a handler evaluated to — not just the inputs and outputs of the handler as a whole. Leverages [re-frame-debux](https://github.com/day8/re-frame-debux)'s `fn-traced` macro as a runtime instrumentation engine, driven via the REPL — no source edits, no recompile.

**Prerequisite:** `day8.re-frame/tracing` must be on the classpath. If it isn't, ask the user to add it to dev deps; you can't conjure macros that aren't loaded.

**Pick the granularity:**

- **A single expression** — wrap a let-binding RHS, a `->` step, or a one-off call with `dbg`. Lighter than wrapping the whole handler; doesn't require restoring anything. See *"Trace a single expression at the REPL"* below.
- **A whole handler / sub / fx** — wrap once, dispatch, read every form's value. Use the wrap-handler!/fn-traced procedure on this page.

**Detect which API is available:**

```
scripts/eval-cljs.sh '(re-frame-pair.runtime/debux-runtime-api?)'
```

`true` → use the runtime-API path below (preferred). `false` → fall back to the manual fn-traced path further down.

**Procedure (runtime API — preferred, debux ≥ 4ed07c9):**

`day8.re-frame.tracing.runtime/wrap-handler!` saves the original handler verbatim into a side-table and re-registers a `fn-traced`-wrapped version under the same id. `unwrap-handler!` restores from the side-table — no source-eval round trip, interceptor chain comes back intact.

1. **Wrap.** The macro takes `[kind id (fn [args] body)]`. Read the handler's body from source (or `scripts/handler-source.sh :event :foo/bar`) and pass it as a literal `fn`:

   ```
   scripts/eval-cljs.sh '(day8.re-frame.tracing.runtime/wrap-handler!
                           :event :cart/apply-coupon
                           (fn [db [_ code]]
                             (-> db
                                 (assoc-in [:cart :coupon] code)
                                 (assoc-in [:cart :coupon-status] :applied))))'
   ```

   `kind` dispatches the matching `reg-event-db` / `reg-sub` / `reg-fx`, so you don't have to match the registration form yourself. Use `wrap-event-fx!` / `wrap-event-ctx!` for events that need the fx- or ctx-shaped interceptor chain.

2. **Dispatch with `--trace`:**

   ```
   scripts/dispatch.sh --trace '[:cart/apply-coupon "SPRING25"]'
   ```

3. **Read `:debux/code`** off the returned epoch. Each entry has `{:form, :result, :indent-level, :syntax-order, :num-seen}` — the form text (post `tidy-macroexpanded-form`), the value it evaluated to, nesting depth, and evaluation order. Walk inner-to-outer to see what each sub-form produced.

4. **Unwrap** to restore:

   ```
   scripts/eval-cljs.sh '(day8.re-frame.tracing.runtime/unwrap-handler! :event :cart/apply-coupon)'
   ```

   Returns `true` if a wrap was found and undone, `false` if `[kind id]` wasn't wrapped (no-op). Always pair wrap with unwrap in the same REPL turn.

**Procedure (manual fn-traced — fallback for debux < 4ed07c9):**

The runtime API is a thin wrapper over the same fn-traced macro; you can still drive it by hand:

1. **Look up the handler** so you can restore it later. CLJS fn values don't pretty-print, but the registrar's stored value plus the original `reg-event-db` form in the user's source is enough to restore.

   ```
   scripts/eval-cljs.sh '(re-frame.registrar/get-handler :event :cart/apply-coupon)'
   ```

2. **Wrap and re-register** with `fn-traced`. Match the original arity AND registration kind (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`):

   ```
   scripts/eval-cljs.sh '(re-frame.core/reg-event-db
                           :cart/apply-coupon
                           (day8.re-frame.tracing/fn-traced [db [_ code]]
                             (-> db
                                 (assoc-in [:cart :coupon] code)
                                 (assoc-in [:cart :coupon-status] :applied))))'
   ```

3. **Dispatch with `--trace`** and read `:debux/code` off the returned epoch (same as step 2-3 of the runtime-API path).

4. **Restore.** Re-eval the original `reg-event-db` form from the user's source. If their source isn't accessible from the REPL, ask the user to hot-reload (saving any source file in the same namespace re-evaluates the original `reg-event-db`).

**Limits to call out to the user:**

- **Classpath only.** This recipe needs `day8.re-frame/tracing` already loaded. If it isn't, fall back to `repl/eval` with manual `tap>` probes around the handler body.
- **`reg-*` / var-backed handlers only.** Handlers that were inlined into other fns at compile time can't be traced this way — wrapping operates on *registration*, not on previously compiled call sites.
- **Body has to be a literal `(fn ...)`.** `fn-traced` operates on the AST at compile time; you cannot pass an already-compiled fn value. Both paths require the body to be a literal `fn` form at REPL-eval time.
- **Same-shape arity.** The wrapped form has to match the original handler's argument shape (`[db ev]` for `reg-event-db`, `[ctx ev]` for `reg-event-fx`, etc.). Look up `registrar/describe :event :foo/bar` first to confirm — `:reg-event-db` vs `:reg-event-fx` lives in the response's `:kind`.
- **Restore is critical.** A wrapped handler stays wrapped for the rest of the REPL session (until full page reload). Always pair wrap with unwrap (or the manual restore) in the same turn.

This recipe is the on-demand half of the integration described in [`docs/inspirations-debux.md` §3.0](./docs/inspirations-debux.md). The bridge half (surfacing `:code` as `:debux/code` in the epoch) is automatic — see the `Trace` op table.

### "Trace a single expression at the REPL"

When the form you want instrumented is a single expression — a let-binding's RHS, a `->` thread step, an inner `(some-fn args)` — and wrapping the whole handler is overkill. `dbg` (rfd-btn, `day8.re-frame.tracing/dbg`) emits one trace record per evaluation: the quoted form, its result, and any opt extras (`:name` / `:locals` / `:if` / `:tap?`). Inside a re-frame event handler the trace lands on `:tags :code` (same surface as fn-traced — it surfaces as `:debux/code` on the coerced epoch); outside any trace context, it falls back to `tap>`.

**Detect availability:**

```
scripts/eval-cljs.sh '(re-frame-pair.runtime/dbg-macro-available?)'
```

`true` → debux ships rfd-btn, the recipe below works. `false` → the host's debux is older (pre-rfd-btn); use the handler-level fn-traced path on this page instead.

**Procedure:**

1. **Identify the form** to instrument. Read the handler's source (`scripts/handler-source.sh :event :foo/bar` to locate, then your editor or the user's source).
2. **Hot-swap the handler with `dbg` wrapping the form of interest.** Same shape as the manual fn-traced path — `reg-event-db` re-eval'd with the body editor:

   ```
   scripts/eval-cljs.sh '(re-frame.core/reg-event-db
                           :cart/apply-coupon
                           (fn [db [_ code]]
                             (-> db
                                 (assoc-in [:cart :coupon]
                                           (day8.re-frame.tracing/dbg
                                             (normalize-coupon code)
                                             {:name "normalize"}))
                                 (assoc-in [:cart :coupon-status] :applied))))'
   ```
3. **Dispatch with `--trace`** and read the resulting epoch's `:debux/code` — the entry whose `:name` is `"normalize"` is your one form's trace; surrounding entries (if the handler also includes other dbg calls) are the others.
4. **Restore.** Re-eval the original `reg-event-db` form (or ask the user to hot-reload the source file).

**Out-of-trace use:** `dbg` works at the bare REPL too — call it on any expression and the result surfaces via `tap>`:

```
scripts/eval-cljs.sh '(do (add-tap (fn [v] (.log js/console (pr-str v))))
                          (day8.re-frame.tracing/dbg (some-pure-calculation 42))
                          :ok)'
```

The tap> payload carries `:debux/dbg true` so a custom tap fn can branch on it.

**Why this over fn-traced:** `dbg` has no AST-walk — it instruments exactly the expression you point at, no more. When the user's question is "what did *this specific call* return?" it's faster, lower-noise, and doesn't require pairing with `unwrap-handler!` / re-eval. For "show me every form in this handler," prefer the handler-level recipe above; for "this one let-binding," prefer `dbg`.

**Limits:**

- **`day8.re-frame/tracing` ≥ rfd-btn.** Older debux releases don't ship `dbg`; check `dbg-macro-available?` first.
- **Hot-swap still required.** `dbg` instruments at macro-expansion time, so the form has to be re-eval'd through the REPL with the `dbg` wrap in place. You can't retroactively `dbg` a form that's already compiled.
- **`:locals` is caller-supplied.** `dbg` can't introspect `&env` portably across CLJ/CLJS the way `fn-traced` does at function-arg time. If you want locals captured, pass them explicitly: `(dbg form {:locals [['db db] ['x x]]})`.

### "Explain this dispatch"

Run `trace/dispatch-and-settle` (or read a recent epoch), then narrate the six dominoes:

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

When multiple views subscribe to the same query-v and you want to know which call site is involved (e.g. the same `[:cart/total]` is read by both the header and the panel), each `:subs/ran` entry carries `:subscribe/source` — the `{:file :line}` of the `(rf.macros/subscribe ...)` call site that established the reaction. Pair this with `:input-query-sources` (vec parallel to `:input-query-vs`) to see where each input dep was subscribed to inside the parent sub handler. Both are populated only when subscribed via `re-frame.macros/subscribe` (rf-cna) — `nil` for bare-fn subscribes. Cache-hit subtlety: when a subscription is shared, `:subscribe/source` reflects the *first* caller's site (re-frame's cache key ignores meta, so subsequent callers reuse the cached reaction with its original meta).

### "Where in the code does this come from?"

Call `dom/source-at` on the element (or on `:last-clicked`). Return `{:file :line}`. If `:src` is nil, report which prerequisite is missing (re-com debug off, or this specific call site wasn't passed `:src (at)`).

### "Where in the code is this handler?"

Call `handler/source` for the handler-id (e.g. `scripts/handler-source.sh :event :cart/apply-coupon`). Two paths produce a hit:

1. **Registration macro (opt-in, reliable):** if the handler was registered via `re-frame-pair.runtime/reg-event-db` (or `reg-event-fx` / `reg-sub` / `reg-fx`), the call site is captured at compile time and stored in a side-table. Response carries `:source :registration-macro`.
2. **Compiled-fn meta (best-effort):** otherwise the op reads `(meta f)` on the registered fn (drilling into the terminal interceptor's `:before` for `:event`). Response carries `:source :fn-meta`. This path usually *fails* on real shadow-cljs builds — re-frame's interceptor wrapper closes over the user fn, and `(meta wrapper)` is nil regardless of source-map config (see `docs/handler-source-meta.md`).

If the response is `:no-source-meta`, say so and fall back to grepping `'(reg-event-'` for the id — don't invent a path. To make `handler/source` reliable, suggest the user opt in:

```clojure
(ns app.events
  (:require [re-frame-pair.runtime :as rfpr
             :refer-macros [reg-event-db reg-event-fx reg-sub reg-fx]]))

(rfpr/reg-event-db :cart/apply-coupon
  (fn [db [_ code]] ...))
```

Drop-in for re-frame's macros — same arities, same semantics, plus the side-table entry. The fixture's `:counter/inc` handler is the worked example.

### "Why did this event fire?"

Two clicks: the dispatch call site (where the event was queued from), and the handler definition (where its body lives). One epoch read covers both.

1. `trace/last-claude-epoch` (or `trace/last-epoch` / `trace/epoch`) → read `:event/source`. That's the `{:file :line}` of the `(rf.m/dispatch [...])` call site — i.e. the click handler, sub-handler, or top-level call that queued this event.
2. `handler/source` for the event-id (e.g. `scripts/handler-source.sh :event :cart/apply-coupon`) — that's the `(reg-event-* :cart/apply-coupon ...)` definition. See *"Where in the code is this handler?"* for the two paths and their reliability tradeoffs.

`:event/source` is `nil` when the event was dispatched via the bare `re-frame.core/dispatch` fn (no macro) or on a re-frame predating rf-hsl. To make it reliable, suggest the user replace `re-frame.core/dispatch` imports with `re-frame.macros/dispatch`:

```clojure
(ns app.click
  (:require [re-frame.macros :as rf.m]))

(defn on-click [e]
  (rf.m/dispatch [:cart/apply-coupon "SPRING25"]))
```

Drop-in for `re-frame.core/dispatch` — same args, same semantics. Debug-only: the macro emits `(if re-frame.interop/debug-enabled? (... vary-meta wrap ...) (re-frame.core/dispatch ev))`, and Closure's `:advanced` DCE strips the meta-wrap branch in production builds where `goog.DEBUG` is false. Zero production overhead.

### "Understand this component" / "What is this thing?"

When the user points at a UI element (CSS selector, *"the thing I last clicked"*, or a description), chain:

1. `dom/source-at` — resolve to `{:file :line}`.
2. `Read` the source file at that line, with ~30 lines of context.
3. Narrate: what the component is, what props it takes, which event(s) its interactions dispatch, and (if you can see them nearby) which subscriptions it reads.
4. If `data-rc-src` isn't resolvable, fall back to `dom/describe` to report tag/class/listeners, and ask the user to point at the source instead.

### "Fire the button at file:line"

Use `dom/fire-click-at-src`. Report the resulting epoch. Useful when you want to exercise a specific call site by its source location rather than picking a CSS path — distinctive to re-frame-pair. Tell the user if `:src (at)` is missing on that call site.

### "What did this dispatch log?"

Ground console output to a specific dispatch by pairing `console/tail` with `trace/dispatch-and-settle`:

1. `scripts/console-tail.sh` once first to read the current `:next-id` — that's your watermark.
2. Fire the dispatch via `scripts/dispatch.sh --trace '[:foo ...]'`.
3. `scripts/console-tail.sh --since-id <watermark>` — every entry that landed during the dispatch. Filter by `--who claude` if the user's app also logs during the same window and you want only the agent-driven side.
4. `--who handler-error` surfaces the synthesised stack from `tagged-dispatch-sync!`'s catch when an event handler threw — pair with the structured `:reason :handler-threw` response.

### "Dead code scan"

`registrar/list :event` and `registrar/list :sub`. Then `trace/recent` with a large window (e.g. 60s) — or ask the user to exercise the app first. Report registered ids that never appeared. *Caveat the user that trace coverage is not exhaustive.*

### Experiment loop

Same starting `app-db`, same event, only the code changes — any difference in the resulting epoch is attributable to your edit. Use whenever you're unsure if a change has the intended effect. Prerequisites: see *Time-travel* — 10x loaded with `:app-db-follows-events?` enabled.

Canonical procedure:

1. `trace/dispatch-and-settle [:foo ...]` → observe baseline. Capture the epoch id.
2. `undo/status` to see what side effects since the epoch of interest can't be rewound (`:http-xhrio`, navigation, landed `:dispatch-later`); warn user.
3. `undo/step-back` or `undo/to-epoch <id>` → rewind `app-db`.
4. **Modify the part of the system you're iterating on.**
   - *Handlers / subs / fx:* `(rf/reg-event-db :foo ...)` / `(rf/reg-sub :bar ...)` / `(rf/reg-fx :baz ...)` via `repl/eval`. Registrar picks up the new definition immediately.
   - *Views / helpers (plain `defn`s):* redefine the var via `repl/eval` — e.g. `(defn my-view [] ...)` in the appropriate namespace. Subsequent Reagent re-renders pick up the new fn.
   - *Permanent change:* `Edit` the source file, then `scripts/tail-build.sh --probe '...'` to wait for the reload to land.
5. **Verify the patch took before re-dispatching.** `registrar/describe :event :foo` (for a handler) should now return a different form/hash than what you captured at step 1. If the patch didn't land, re-dispatching will silently test the old code.
6. `trace/dispatch-and-settle [:foo ...]` → observe the new behaviour.
7. Compare the two epochs. Repeat until satisfied.
8. If the change was REPL-only and the user wants to keep it, *commit via source edit* — REPL changes are lost on full page reload.

**Side-effecting handlers — stub at the dispatch site.** When step 1 / step 6 would fire `:http-xhrio`, navigation, local-storage etc. that you don't want to actually run during a probe, pass `--stub <fx-id>` to `dispatch.sh` (one flag per fx to substitute):

```
scripts/dispatch.sh --trace --stub :http-xhrio --stub :navigate '[:user/login {...}]'
```

Each named fx is replaced with a record-only stub for that single dispatch (and its cascade — children queued via `:fx [:dispatch ...]` inherit the substitution; rf-ge8). The captured effect values land in the session log:

```
scripts/eval-cljs.sh '(re-frame-pair.runtime/stubbed-effects-since 0)'
;; → {:ok? true :entries [{:fx-id :http-xhrio :value {...} :ts ...}] :now ...}
```

The epoch returned by `--trace` includes `:stubbed-fx-ids` so you can verify which substitutions applied. No global state to restore — the override is event-meta, expires when the cascade finishes.

When you need a stub fn that does something other than record-and-drop (e.g. deterministic on-success callback), call `(re-frame-pair.runtime/dispatch-with! [:ev] {:http-xhrio (fn [req] ...)})` directly via `eval-cljs.sh` — fns can't round-trip the bash CLI.

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
- `:timed-out? true` on a dispatch-and-settle → the cascade never settled within :timeout-ms (tab backgrounded, debugger paused, async chain re-firing past the quiet-period); ask the user to bring the tab forward, or bump `:timeout-ms` / `:settle-window-ms`.
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
