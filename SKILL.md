---
name: re-frame-pair
description: >
  Pair-program with a live re-frame application via shadow-cljs nREPL.
  Inspect app-db, dispatch events, hot-swap handlers, trace dispatches,
  read 10x's epoch buffer, time-travel — without source edits when probing.
  Use whenever the user asks about their running re-frame app or mentions:
  re-frame, app-db, dispatch, subscribe, reg-event, reg-sub, reg-fx, epoch,
  interceptor, re-frame-10x, re-com, shadow-cljs.
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

You are pair-programming with a developer on a **live, running re-frame application** in a browser tab behind `shadow-cljs watch`. Without re-frame-pair you'd be reading source and guessing — you couldn't see `app-db`, you couldn't ask *"why didn't my view update?"* against actual state, you couldn't probe a fix without committing it. With re-frame-pair, you operate against the real runtime: read the current `app-db`, dispatch events, see *why* a sub didn't fire, hot-swap a handler to test a hypothesis, time-travel.

Your agency runs through two coupled primitives:

1. **The REPL** — a shadow-cljs nREPL session connected to the browser runtime. ClojureScript forms evaluate against the real app.
2. **re-frame-10x's epoch buffer** — the trace of every event already collected by 10x. One *epoch* per dispatch: handler ran, subs ran, components rendered. Read from it; never register a second trace callback.

Every op below becomes a short ClojureScript form evaluated through the REPL, usually against a helper in the `re-frame-pair.runtime` namespace that the skill injects on connect.

## Quick start

Three calls and you're inside the app:

```bash
$ scripts/discover-app.sh
{:ok? true :session-id "..." :ten-x-loaded? true :trace-enabled? true ...}

$ scripts/app-summary.sh
{:versions {...} :registrar {:event [...]} :subs/live [[...]] :app-db {...}}

$ scripts/dispatch.sh --trace '[:cart/apply-coupon "SPRING25"]'
{:ok? true :event [...] :epoch {:event/source {:file ...} :app-db/diff {...} :renders [...] ...}}
```

The rest of this doc is the vocabulary on top.

## When NOT to use re-frame-pair

- The user is starting an app that isn't running yet (no live runtime to attach to).
- They're debugging code that isn't routed through re-frame.
- They want pure source-only refactor work with no behavioural verification.

## What every coerced epoch carries

Every epoch returned by `trace/*` ops has this shape. These fields are the headline new powers re-frame-pair gives you over reading source — every epoch tells you not just *what* happened, but *where in source* it was wired up.

| Field | What it tells you |
|---|---|
| `:event` | The dispatched event vector — what the handler saw (post-interceptor rewrites). |
| `:event/original` | The dispatched event pinned at handle-entry — *before* any interceptor (`trim-v`, `unwrap`, `path`) rewrote it. |
| `:event/source` | `{:file :line}` of the dispatch call site — when the host opted in via `re-frame.macros/dispatch`. **`nil`** otherwise. |
| `:dispatch-id` / `:parent-dispatch-id` | UUIDs threaded through cascades. Cascade children carry the parent's id; root events have `:parent-dispatch-id nil`. |
| `:app-db/diff` | `{:before :after :only-before :only-after}` — what changed. Compact, not the full before/after. |
| `:effects/fired` | Effects flattened to a tree — `:db`, `:dispatch`, `:http-xhrio`, custom fx. |
| `:interceptor-chain` | Ordered `:id` keys of the chain that ran. |
| `:subs/ran` | Each entry: `:query-v :subscribe/source :input-query-vs :input-query-sources :time-ms`. |
| `:subs/cache-hit` | Subs that didn't re-run; same shape as `:subs/ran`. `:subscribe/source` resolves which view originated the cached reaction. |
| `:renders` | Components that re-rendered, with `:re-com? :re-com/category :src`. |
| `:debux/code` | When the handler is wrapped with `fn-traced` (re-frame-debux), per-form `{:form :result :indent-level :syntax-order}`. `nil` otherwise. Stripped from `dispatch.sh --trace`'s slim payload — recover via `(epoch-by-id <id>)`. |
| `:coeffects` | Injected coeffects (`:db`, `:event`, custom). |

## Source-meta capture (host opt-in)

For `:event/source` and `:subscribe/source` to populate, the host app must alias-swap their re-frame `:require`. Function API → macro mirror, drop-in:

```clojure
;; before — function API, no source-meta
(:require [re-frame.core   :as rf])

;; after — same call shape, source-meta captured at expansion
(:require [re-frame.macros :as rf])
```

Production builds (`goog.DEBUG=false`) elide the meta-attach via Closure DCE — zero allocation overhead.

**Caveats:**
- Macros can't be used in value position. For `(apply reg-sub ...)`, `(map reg-event-db ...)`, `(partial reg-fx ...)`, keep `re-frame.core`.
- `:event/source` from `dispatch.sh --trace` stays nil — the bash shim dispatches via `re-frame.core/dispatch` (function API), not the macro. Real button clicks DO carry `:event/source` from the view's macro call site.
- On re-frame builds predating the macros, both fields stay nil; flag the version-floor and proceed.

When `handler/source` returns `:no-source-meta`, the cause is most often that the host hasn't done the alias-swap. Tell them; the change is one require line per ns. See [`docs/handler-source-meta.md`](docs/handler-source-meta.md) for the design rationale and history.

## Operating principles

- **Read live state before guessing.** `app-db/snapshot`, `trace/last-epoch` first; hypothesis after.
- **Probe, don't speculate.** When an answer isn't obvious, evaluate against live data.
- **REPL access is your second mode.** You can hot-swap a handler / sub / fx, redefine a `defn`, or `reset!` `app-db` directly through `repl/eval` — the change takes effect immediately in the running app, no source edit and no recompile. That makes probing cheap: try a fix, dispatch the event, watch the resulting epoch, throw it away. When a REPL-only patch turns out to be the right shape, transfer it to source. Two practical points to remember:
  - REPL changes are **ephemeral** — survive hot-reloads of unaffected nses, lost on full page refresh. Source edits via `Edit` / `Write` are **permanent**.
  - After any source edit, run `scripts/tail-build.sh --probe '<form>'` before dispatching or tracing — otherwise you're interacting with the pre-reload code.
- **Connect first, every session.** Run `scripts/discover-app.sh` before any other op. discover-app finds the nREPL port, switches to `:cljs` mode, verifies preconditions, injects the runtime ns, and returns `:startup-context` with the current app-db snapshot plus a compact tail of recent events. Run `scripts/app-summary.sh` next when you need registrar inventory, live subs, and app-db shape. If discover fails it returns `{:ok? false :reason ...}` — surface verbatim, don't guess workarounds.
- **Surface failures verbatim.** Every script returns structured edn. Translate `:reason` to plain English; don't paper over it.
- **Validate before proposing.** When a hot-swap or suggestion is on the table, compose the form and run it against current state first.
- **Narrow detail as you go.** Summaries first; drill into a specific epoch / diff / sub when the user asks.
- **Always resolve UI references to `:src`.** `re-com/button at app/cart/view.cljs:84` grounds the conversation; *"the Save button somewhere in the profile view"* doesn't.
- **Prefer dedicated shims over `eval-cljs`.** When a shim exists (`dispatch.sh`, `handler-source.sh`, etc.), it removes three sources of typo (the namespace name, the fn name, edn quoting) and exposes flag combinations the eval form can't compose. Run `<shim> --help` to discover its surface.

### Multi-build setups

If the connected nREPL has more than one shadow-cljs build active (e.g. `:app` and `:storybook` running side by side), `discover` adds `:warning :multiple-builds :picked <id> :others [...]`. Surface the warning to the user — the picked build may not be the one they meant. `scripts/discover-app.sh --list` enumerates candidates without injecting; pick deliberately via `--build=<id>` or `SHADOW_CLJS_BUILD_ID`. Explicit choice suppresses the warning.

Every op auto-reinjects on browser refresh; responses carry `:reinjected? true` when this happens — informational, not an error.

## Operations vocabulary

### Starter pack — the 80% set

These cover most conversations:

| Op | Use it for |
|---|---|
| `scripts/app-summary.sh` | Extended bootstrap: versions, registrar, live subs, app-db shape, health |
| `scripts/dispatch.sh '[:foo ...]'` | Fire an event (queued; `--sync` for sync; `--trace` for full epoch + cascade) |
| `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-epoch)'` | Most recent epoch, fully coerced |
| `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-where <pred>)'` | Forensic — most recent epoch matching a predicate |
| `scripts/handler-source.sh :event :foo/bar` | `{:file :line}` of a registered handler |
| `scripts/eval-cljs.sh '(re-frame-pair.runtime/subs-live)'` | Currently-cached query vectors |
| `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-source-at "#save-btn")'` | DOM element → source line |

### Read

| Op | Invocation | Returns |
|---|---|---|
| `app/summary` | `scripts/app-summary.sh` | Extended bootstrap bundle. Use after discover when you need registrar/live-sub detail. |
| `health` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/health)'` | Re-arms listeners; `{:ok? :session-id :ten-x-loaded? :trace-enabled? :native-epoch-cb? :rf-error-handler? ...}`. Lighter than `app/summary` when you only need health. |
| `versions/report` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/version-report)'` | Per-dep observed/floor + `:enforcement-live?` (distinguishes "all floors satisfied" from "no floors set, vacuous OK"). |
| `app-db/snapshot` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/snapshot)'` | Current `@app-db` |
| `app-db/get` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/app-db-at [:path])'` | Path-scoped value |
| `app-db/schema` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/schema)'` | Opt-in schema or `nil` |
| `registrar/list` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/registrar-list :event)'` | Ids under kind `:event`/`:sub`/`:fx`/`:cofx` |
| `registrar/describe` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/registrar-describe :event :foo/bar)'` | Kind + interceptor ids |
| `subs/live` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/subs-live)'` | Currently-subscribed query vectors |
| `subs/sample` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/subs-sample [:cart/total])'` | One-shot deref |
| `handler/source` | `scripts/handler-source.sh :event :foo/bar` | `{:file :line}` of the handler. Returns `:no-source-meta` cleanly when the host hasn't opted into the macros — see *Source-meta capture*. |
| `handler/ref` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/registrar-handler-ref :event :foo/bar)'` | Opaque hash of the registered handler — stable across reads, **flips on hot-swap or reload**. The canonical `tail-build.sh --probe` form. |

### Write

| Op | Invocation | Notes |
|---|---|---|
| `dispatch` | `scripts/dispatch.sh '[:foo ...]'` | Queued by default; `--sync` for `dispatch-sync`; `--stub :http-xhrio` substitutes a record-only stub for the named fx in this dispatch and its cascade |
| `reg-event` / `reg-sub` / `reg-fx` | `scripts/eval-cljs.sh '<full reg-* form>'` | Hot-swap immediately. Ephemeral. |
| `app-db/reset` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/app-db-reset! ...)'` | Logged via `tap>` so the user sees it. Use sparingly. |
| `repl/eval` | `scripts/eval-cljs.sh '<form>'` | Escape hatch. Prefer structured ops first. |

### Trace (read-only from the epoch buffer)

| Op | Invocation | Returns |
|---|---|---|
| `trace/last-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-epoch)'` | Most recent epoch (any origin) |
| `trace/last-claude-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-claude-epoch)'` | Most recent epoch this session dispatched |
| `trace/epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/epoch-by-id "<id>")'` | Named epoch (full payload, including `:debux/code`) |
| `trace/dispatch-and-settle` | `scripts/dispatch.sh --trace '[:foo ...]'` | Fire + await the cascade + return root and cascaded epochs (rf-4mr; falls back to fixed-sleep on older re-frame) |
| `trace/recent` | `scripts/trace-recent.sh <ms>` | Epochs added in last N ms (pull) |
| `trace/find-where` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-where <pred>)'` | Most recent epoch matching a predicate — primary forensic op |
| `trace/find-all-where` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-all-where <pred>)'` | Every matching epoch, newest first — for trajectories rather than single transitions |

The shape of each returned epoch is documented in *What every coerced epoch carries* above.

**Trace-stream schema.** `re-frame.core/tag-schema` (re-frame 2026 release) describes the `:tags` map for every op-type re-frame emits. Doc-only by default; downstream tooling reads it as a load-bearing contract. When investigating a trace-shape mismatch, the user can opt into runtime validation: `scripts/eval-cljs.sh '(re-frame.core/set-validate-trace! true)'` warns via `console :warn` on missing required keys or unknown keys. `re-frame.core/validate-trace?` returns the current state. Both surface live `nil` on re-frame predating the 2026 release.

### Console / errors

A ring buffer of `js/console.{log,warn,error,info,debug}` calls captured by the runtime, tagged with `:who` so you can ask "what did MY dispatch log, vs the user's app, vs which handler threw?". Installed by `health` (idempotent, max 500 entries).

**`:who` values.** `:claude` for entries during your `tagged-dispatch-sync!` (sync only — async-queued handlers tag `:app`); `:app` for everything else; `:handler-error` synthesised when re-frame's event-error-handler catches a throw (browser-side handler exceptions land here uniformly with rfp-driven dispatch errors).

| Op | Invocation | Returns |
|---|---|---|
| `console/tail` | `scripts/console-tail.sh` | All buffered entries newest-last |
| `console/tail-since` | `scripts/console-tail.sh --since-id 42` | Entries with `:id >= 42` (use `:next-id` from previous call to tail incrementally) |
| `console/tail-claude` | `scripts/console-tail.sh --who claude` | Only entries tagged `:claude` |
| `console/tail-handler-errors` | `scripts/console-tail.sh --who handler-error` | Synthesised entries from re-frame's event-error-handler |

### DOM ↔ source bridge (re-com `:src`)

When a re-com component is called with `:src (at)`, re-com attaches `data-rc-src="file:line"` to the rendered element — a two-way bridge between live DOM and the source line that produced it. Use it whenever the conversation is about a visible element.

**Prerequisites** — both must hold for a specific element's `:src` to resolve:
- re-com's debug instrumentation enabled in the dev build (a config flag in `re-com.config`)
- the component's call site passed `:src (at)`

**Degradation is per-element, not app-wide.** An app with `:src (at)` on most components but not all works fine — the bridge resolves where annotations are present and returns `{:src nil :reason :no-src-at-this-element}` for the few that aren't. When re-com debug is *entirely* off, every element returns `{:src nil :reason :re-com-debug-disabled}`. Tell the user which case they're hitting.

| Op | Invocation | Returns |
|---|---|---|
| `dom/source-at` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-source-at "#save-button")'` or `'(... :last-clicked)'` | `{:file :line}` for a CSS selector, or for the most recently clicked element |
| `dom/find-by-src` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-find-by-src "view.cljs" 84)'` | Live DOM elements rendered by that source line |
| `dom/fire-click-at-src` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-fire-click "view.cljs" 84)'` | Synthesise a click on the element rendered by that line |
| `dom/describe` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-describe "#save-button")'` | Attrs + `data-rc-src` + attached listeners |

### Live watch (push-mode)

| Op | Invocation | Behaviour |
|---|---|---|
| `watch/window` | `scripts/watch-epochs.sh --window-ms 30000 --event-id-prefix :checkout/` | Run for N ms, report every match, summarise at end |
| `watch/count` | `scripts/watch-epochs.sh --count 5` | Run until N epochs match |
| `watch/stream` | `scripts/watch-epochs.sh --stream --event-id-prefix :cart/` | Stream until disconnect, idle-timeout, or `watch/stop` |
| `watch/stop` | `scripts/watch-epochs.sh --stop` | Terminate any active watch for this session |

Predicates (any combination): `--event-id`, `--event-id-prefix`, `--effects`, `--timing-ms '>100'`, `--touches-path`, `--sub-ran`, `--render`.

v1 transport is **pull-mode** — repeated short evals at ~100ms cadence.

### Hot-reload coordination

After any source edit, before the next dispatch or trace:

```
scripts/tail-build.sh --wait-ms 5000 --probe '(some/probe-form)'
```

`--probe` is a CLJS form chosen to change when the edited code reloads (see *Hot-reload protocol* below). If you don't know a good probe, omit `--probe` and the script falls back to a 300ms timer; the result includes `:soft? true` so you know it's timer-based.

### Time-travel (adapter over 10x internals)

These ops dispatch into re-frame-10x's *inlined* re-frame instance to navigate the epoch buffer. Each navigation event triggers `::reset-current-epoch-app-db` — `(reset! userland.re-frame.db/app-db <pre-state>)` — but ONLY when 10x's `:settings :app-db-follows-events?` is true (default true; users can toggle from 10x's Settings panel). `undo-status` reports the current setting. When 10x isn't loaded, ops fail with `{:reason :ten-x-missing}`.

When `day8.re-frame-10x.public` (rf1-jum) is loaded, the navigation events route through the public string identifiers (`previous-epoch` / `next-epoch` / etc.) via the public `dispatch!` bridge — durable contract. Older 10x JARs fall back to the internal `day8.re-frame-10x.navigation.epochs.events` keywords automatically.

| Op | Invocation | Purpose |
|---|---|---|
| `undo/step-back` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-step-back)'` | Rewind one epoch |
| `undo/step-forward` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-step-forward)'` | Redo |
| `undo/most-recent` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-most-recent)'` | Jump to head |
| `undo/to-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-to-epoch <id>)'` | Jump to specific epoch |
| `undo/replay` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-replay)'` | Re-fire the selected event |
| `undo/status` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-status)'` | `:selected-epoch-id`, `:back-count`, `:forward-count`, `:total-epochs`, `:app-db-follows-events?` |

**Caveat (tell the user):** undo rewinds `app-db` only. Side effects that already fired (`:http-xhrio`, navigation, `:dispatch-later`) are *not* undone. Warn before an experiment that depends on clean state. If `:app-db-follows-events?` is false, navigation succeeds but `app-db` doesn't move — ops surface `:warning :app-db-follows-events?-disabled` so you can flag this.

## Hot-reload protocol

Editing source is legitimate and often correct. The protocol is strict:

1. Make the edit with `Edit` / `Write`.
2. Call `scripts/tail-build.sh` with a `--probe` that verifies the browser has the new code:
   - For an edited `reg-*` handler, the canonical probe is `handler/ref` — its hash flips on reload.
   - For a view or helper, the probe is a short form that reads a value depending on the edited code.
   - If no good probe is available, omit `--probe` and accept the soft/timer-based confirmation.
3. Only after the probe succeeds do you proceed to `dispatch`, `trace/*`, etc.
4. If the probe times out, treat that as a compile error in the user's code — read the tail output, report it to the user, do *not* retry dispatching.

## Recipes (named procedures the user may ask for)

When the user asks a matching question, run the procedure rather than improvising.

### Short answers (one-look recipes)

#### "What's in `app-db`?" / "What did the last event do?"

`app-db/snapshot`, `app-db/get`, or for a diff: `trace/last-epoch` → `:app-db/diff`.

#### "What effects fired?"

Walk `:effects/fired` from the epoch as a tree. Follow `:dispatch-id` / `:parent-dispatch-id` links into child epochs for `:dispatch*` effects (cascade tracking). Flag pending effects (`:dispatch-later` not yet fired, `:http-xhrio` still awaiting response) as "queued, not landed."

#### "What caused this re-render?"

Given a component name or `:src`, find the latest epoch whose `:renders` includes it. Reverse from there: the sub inputs that invalidated its outputs, then the event that invalidated the sub inputs. When multiple views subscribe to the same query-v and you want to know which call site, see *"Which view subscribed to X?"* below.

#### "Where in the code does this come from?"

`dom/source-at` on the element (or `:last-clicked`). If `:src` is nil, report which prerequisite is missing (re-com debug off, or this specific call site wasn't passed `:src (at)`).

#### "Where in the code is this handler?"

`handler/source` for the handler-id. Returns `{:ok? true :file ... :line ... :source :fn-meta}` when the call site is reachable, `:no-source-meta` when the host hasn't opted into the macros (see *Source-meta capture*; the alias-swap is usually one require line per ns).

#### "Fire the button at file:line"

`dom/fire-click-at-src`. Report the resulting epoch. Tell the user if `:src (at)` is missing on that call site.

#### "Dead code scan"

`registrar/list :event` and `registrar/list :sub`. Then `trace/recent` with a large window (e.g. 60s) — or ask the user to exercise the app first. Report registered ids that never appeared. *Caveat the user that trace coverage is not exhaustive.*

#### "Narrate the next N events"

`watch/count N` with no filter. Report each epoch as a short paragraph (event id, key `:effects/fired`, `app-db` diff summary) as it fires.

#### "Alert me on slow events"

`watch/stream --timing-ms '>100'`. Silent until a match; report with interceptor timing breakdown when one hits.

#### "Watch for X while I interact"

`watch/stream --event-id-prefix :checkout/` (or other predicate). Narrate each match; summarise when idle.

### Procedures (multi-step recipes)

#### "Why didn't my view update?"

1. Identify the sub the view reads (ask the user if it's not in the view file).
2. `trace/last-claude-epoch` or `trace/last-epoch` — find the recent dispatch that should have updated it.
3. Walk the Layer 2 → Layer 3 chain of that sub. For each layer, compare the pre- and post-epoch value.
4. Report the equality gate that held the value constant ("Layer 2 at `[:user/profile]` returned the same map both times, so Layer 3 at `[:user/display-name]` short-circuited").

#### "Is the rendered output correct?" / "Verify the view matches the data"

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

#### "Trace a handler / sub / fx form-by-form"

When the user wants to see what each *expression inside* a handler evaluated to. Leverages [re-frame-debux](https://github.com/day8/re-frame-debux)'s `fn-traced` macro as a runtime instrumentation engine, driven via the REPL — no source edits, no recompile.

**Prerequisite:** `day8.re-frame/tracing` on the classpath. Probe with `(re-frame-pair.runtime/debux-runtime-api?)`.

The full procedure (wrap → dispatch → read `:debux/code` → unwrap), the single-expression `dbg` variant, and the noise-reduction options table live in [`docs/recipes/debux.md`](docs/recipes/debux.md). When the answer landed, recover `:debux/code` from `(epoch-by-id <id>)` — it's stripped from `dispatch.sh --trace`'s slim payload to keep that round-trip small.

##### Tracing options — reducing noise

The handler-level (`fn-traced`) and single-expression (`dbg`) recipes both accept call-time options for filtering what lands on `:debux/code`: `:once` / `:o` (suppress consecutive duplicates), `:final` / `:f` (only outermost result per top-level form), `:msg` / `:m` (developer label), `:verbose` / `:show-all` (wrap leaf literals), `:if` (guard predicate). Plus a public reset for the per-call-site `:once` memory: `(day8.re-frame.tracing/reset-once-state!)`.

Full table with examples and when-to-reach-for-each guidance in [`docs/recipes/debux.md`](docs/recipes/debux.md#tracing-options--reducing-noise).

#### "Explain this dispatch"

Run `trace/dispatch-and-settle` (or read a recent epoch), then narrate the **six dominoes** of re-frame's data loop — one compact paragraph per domino:

1. Event vector + interceptor chain (by id)
2. Coeffects injected
3. Effects returned (`:effects/fired` for a cascade tree)
4. `app-db` diff (changed paths, not the full before/after)
5. Subs that re-ran vs cache-hit
6. Components that re-rendered, with `:src` file:line where available

Keep it short.

#### "Post-mortem — how did we get here?"

When the user is stuck in a broken state and can't describe how they got there.

1. Ask the user what's wrong in *observable* terms ("the save button is grey", "the dashboard is empty"). Resolve any UI references to source via `dom/source-at` if possible.
2. Identify the **app-db key(s) or sub(s)** that govern the observation. If the user can't, trace the recent render for the offending component and walk its sub inputs.
3. Use `trace/find-where` to pinpoint the epoch where the governing key last changed to its current (bad) value:
   ```
   scripts/eval-cljs.sh '(re-frame-pair.runtime/find-where
                           (fn [e] (= :expired (get-in (:only-after (:app-db/diff e))
                                                       [:auth-state]))))'
   ```
4. Report that epoch as the culprit: its event vector, the diff, and (crucially) the `:effects/fired` cascade. Often the root-cause dispatch is a child of another event — follow `:parent-dispatch-id` upstream via `trace/epoch <id>`.
5. If no single epoch is responsible — the state drifted over many events — use `find-all-where` to get the trajectory. Narrate the 3–5 most relevant transitions rather than all of them.
6. Propose a fix. Usually one of: a handler that shouldn't have fired, a handler that did fire but was wrong, or a missing guard.

**Retention caveat.** 10x's epoch buffer is a ring (bounded size). Events that happened "a long time ago" may have aged out — `epochs-since` will return `:id-aged-out? true` and `find-where` will silently not find the transition. If you suspect retention is the limit, say so explicitly: *"I can see the last N events but the change you're describing happened before that."* Then propose reproducing the path from a known state.

#### "Which view subscribed to X?"

When the user wants to know who is reading a particular subscription — e.g. *"the same `[:cart/total]` is read by both the header and the panel; which call site fired this re-render?"*. Each `:subs/ran` or `:subs/cache-hit` entry on a coerced epoch carries `:subscribe/source` — the file/line of the outer `(rf.macros/subscribe ...)` call.

1. **Find live readers of the query.** `subs/live` returns every currently-subscribed query vector; filter to the query of interest:
   ```
   scripts/eval-cljs.sh '(->> (re-frame-pair.runtime/subs-live)
                              (filter (fn [q] (= (first q) :cart/total))))'
   ```
   This tells you the query is alive but not where it was subscribed from — that lives on the trace.

2. **Read `:subscribe/source` from recent sub entries.** Pull a recent epoch and walk its `:subs/ran` plus `:subs/cache-hit`:
   ```
   scripts/eval-cljs.sh '(let [e (re-frame-pair.runtime/last-epoch)]
                            (->> (concat (:subs/ran e) (:subs/cache-hit e))
                                 (filter (fn [s] (= (first (:query-v s)) :cart/total)))
                                 (mapv (juxt :query-v :subscribe/source))))'
   ```
   Each match carries the file/line of the view that asked for the reaction.

3. **For composite subs, walk `:input-query-sources`.** When a Layer 3 sub composes Layer 2 inputs, the parent sub handler's call to `subscribe` for each input lands in a vec parallel to `:input-query-vs`. Useful when the question is *"which of this Layer 3's inputs is recomputing?"* and you want the file/line where each input was wired in.

**Cache-hit subtlety.** When a subscription is shared across multiple views, re-frame's cache key ignores meta — so subsequent callers reuse the cached reaction with the *first* caller's `:re-frame/source`. `:subscribe/source` therefore reflects the originating call site, not necessarily every reader. To enumerate every live reader, fall back to `subs/live` + a grep of the codebase for `(rf.m/subscribe [:cart/total])` invocations.

**Prerequisite:** subscribed via `re-frame.macros/subscribe`. Nil for bare-fn subscribes or pre-2026 re-frame.

#### "Why did this event fire?"

Two clicks: the dispatch call site (where the event was queued from), and the handler definition (where its body lives). One epoch read covers both.

1. `trace/last-claude-epoch` (or `trace/last-epoch` / `trace/epoch`) → read `:event/source`. That's the `{:file :line}` of the `(rf.m/dispatch [...])` call site.
2. `handler/source` for the event-id — that's the `(reg-event-* :foo ...)` definition.

If `:event/source` is nil, the host hasn't opted into `re-frame.macros/dispatch` — see *Source-meta capture* above. The fix is the same alias-swap that powers `handler/source`.

#### "Understand this component" / "What is this thing?"

When the user points at a UI element (CSS selector, *"the thing I last clicked"*, or a description), chain:

1. `dom/source-at` — resolve to `{:file :line}`.
2. `Read` the source file at that line, with ~30 lines of context.
3. Narrate: what the component is, what props it takes, which event(s) its interactions dispatch, and (if you can see them nearby) which subscriptions it reads.
4. If `data-rc-src` isn't resolvable, fall back to `dom/describe` to report tag/class/listeners, and ask the user to point at the source instead.

#### "What did this dispatch log?"

Ground console output to a specific dispatch by pairing `console/tail` with `trace/dispatch-and-settle`:

1. `scripts/console-tail.sh` once first to read the current `:next-id` — that's your watermark.
2. Fire the dispatch via `scripts/dispatch.sh --trace '[:foo ...]'`.
3. `scripts/console-tail.sh --since-id <watermark>` — every entry that landed during the dispatch. Filter `--who claude` for the agent-driven side; `--who handler-error` for re-frame error-handler captures (the `[handler-threw] ...` shape works uniformly for both rfp-driven dispatches and browser-side click dispatches that throw).

#### "Try a change and compare epochs"

The **experiment loop** — same starting `app-db`, same event, only the code changes; any difference in the resulting epoch is attributable to your edit. Use whenever you're unsure if a change has the intended effect. Prerequisites: see *Time-travel* — 10x loaded with `:app-db-follows-events?` enabled.

1. `trace/dispatch-and-settle [:foo ...]` → observe baseline. Capture the epoch id.
2. `undo/status` to see what side effects since the epoch of interest can't be rewound (`:http-xhrio`, navigation, landed `:dispatch-later`); warn user.
3. `undo/step-back` or `undo/to-epoch <id>` → rewind `app-db`.
4. **Modify the part of the system you're iterating on.**
   - *Handlers / subs / fx:* `(rf/reg-event-db :foo ...)` etc. via `repl/eval`. Registrar picks up the new definition immediately.
   - *Views / helpers (plain `defn`s):* redefine the var via `repl/eval` — e.g. `(defn my-view [] ...)` in the appropriate namespace. Subsequent Reagent re-renders pick up the new fn.
   - *Permanent change:* `Edit` the source file, then `scripts/tail-build.sh --probe '...'` to wait for the reload to land.
5. **Verify the patch took before re-dispatching.** Capture `handler/ref :event :foo` before the edit (step 1 / step 4 prerequisite) and again after — the opaque hash is stable across reads but flips when the registered handler is replaced (hot-swap or hot-reload). `registrar/describe` is the *wrong* op for this: its `{:kind :interceptor-ids :source :not-retained}` shape is deterministic across redefs of the same shape, so it would always look unchanged. If the patch didn't land, re-dispatching will silently test the old code.
6. `trace/dispatch-and-settle [:foo ...]` → observe the new behaviour.
7. Compare the two epochs. Repeat until satisfied.
8. If the change was REPL-only and the user wants to keep it, *commit via source edit* — REPL changes are lost on full page reload.

**Side-effecting handlers — stub at the dispatch site.** When step 1 / step 6 would fire `:http-xhrio`, navigation, local-storage etc. that you don't want to actually run during a probe, pass `--stub <fx-id>` to `dispatch.sh` (one flag per fx to substitute):

```
scripts/dispatch.sh --trace --stub :http-xhrio --stub :navigate '[:user/login {...}]'
```

The bundled fixture has a safe custom effect for validating this path:
`scripts/dispatch.sh --trace --stub :test/log-message '[:test/log-then-dispatch "hello"]'`.

Each named fx is replaced with a record-only stub for that single dispatch (and its cascade — children queued via `:fx [:dispatch ...]` inherit the substitution). Captured effect values land in the session log:

```
scripts/eval-cljs.sh '(re-frame-pair.runtime/stubbed-effects-since 0)'
;; → {:ok? true :entries [{:fx-id :http-xhrio :value {...} :ts ...}] :now ...}
```

The epoch returned by `--trace` includes `:stubbed-fx-ids` so you can verify which substitutions applied. No global state to restore — the override is event-meta, expires when the cascade finishes.

Each `--stub <fx-id>` is checked against the `:fx` registrar before the dispatch fires. If you typo the id (e.g. `:http-xhr` for `:http-xhrio`) the shim emits `{:ok? false :reason :unregistered-fx :unknown [:http-xhr] :requested [...]}` instead of dispatching — the override would otherwise be dead weight and the real fx would run unguarded. List registered ids with `scripts/eval-cljs.sh '(re-frame-pair.runtime/registrar-list :fx)'`.

When you need a stub fn that does something other than record-and-drop (e.g. deterministic on-success callback), call `(re-frame-pair.runtime/dispatch-with! [:ev] {:http-xhrio (fn [req] ...)})` directly via `eval-cljs.sh` — fns can't round-trip the bash CLI.

## Error handling

Every script returns structured edn like `{:ok? false :reason ...}` rather than raising. Translate to plain English for the user and suggest the fix named in `:reason`.

Common cases:

- `:nrepl-port-not-found` → tell the user to start their dev build with `shadow-cljs watch <build>`.
- `:browser-runtime-not-attached` → tell the user to open the app in a browser tab.
- `:trace-enabled-false` → the build wasn't compiled with `re-frame.trace.trace-enabled?` = true; this is a 10x requirement.
- `:ns-not-loaded` + namespace → re-frame-10x / re-com not loaded; check their deps.
- `:version-too-old` → report dep + observed vs required version.
- `:handler-error` inside an epoch → the user's handler threw; point at `:handler/error :stack`.
- `:timed-out? true` on a dispatch-and-settle → the cascade never settled within `:timeout-ms` (tab backgrounded, debugger paused, async chain re-firing past the quiet-period); ask the user to bring the tab forward, or bump `:timeout-ms` / `:settle-window-ms`.
- `:connection :lost` → reconnect by calling `scripts/discover-app.sh` again.

## Upstream feature references

The features re-frame-pair consumes from upstream (refer to these when explaining capability gaps):

- **`re-frame.macros`** (re-frame 2026 release) — opt-in macro mirror of `re-frame.core` that captures `{:file :line}` source-meta. Powers `:event/source`, `:subscribe/source`, `:input-query-sources`, and `handler/source`.
- **`register-epoch-cb`** (re-frame 2026) — assembled-epoch callback. Powers the native epoch-buffer path; rfp falls back to reading 10x's epoch buffer on older re-frame.
- **`dispatch-and-settle`** (re-frame 2026) — adaptive cascade quiet-period heuristic. Powers `dispatch.sh --trace`'s settle-aware epochs.
- **`dispatch-with` / `dispatch-sync-with`** (re-frame 2026) — per-dispatch fx-handler substitution via `:re-frame/fx-overrides` event-meta. Powers `--stub <fx-id>`.
- **`day8.re-frame-10x.public`** (re-frame-10x stable public ns) — `latest-epoch-id`, `epoch-count`, `all-traces`, plus `previous-epoch` / `next-epoch` / `most-recent-epoch` / `load-epoch` / `replay-epoch` / `reset-epochs` event identifiers used by `undo/*`.
- **`day8.re-frame.tracing/dbg` and `wrap-handler!`** (re-frame-debux) — single-expression and whole-handler instrumentation. Powers `:debux/code` on coerced epochs. See [`docs/recipes/debux.md`](docs/recipes/debux.md) for the full procedure.
