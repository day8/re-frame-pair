# Companion changes for re-frame

> Audience: a re-frame maintainer. This document proposes six changes to re-frame that re-frame-pair (a Claude Code skill for introspecting re-frame apps) would benefit from. Read [`docs/initial-spec.md`](./initial-spec.md) — particularly Appendix A and §3.2 / §4.3 — for context. **No upstream patches are being proposed yet; this is a survey + design proposal so the changes can be discussed and implemented à la carte if there's agreement.** Each item below is independent.

re-frame-pair currently reaches across several internal seams in re-frame: the registrar atom, the subscription cache, and the trace machinery. Most items here promote those seams to public surface; two (A8, A9) are speculative additions to the dispatch and effects model. **A4 is the keystone:** if re-frame emits epoch-granular callbacks, re-frame-pair stops needing 10x as the trace substrate, and items A6 (provenance) and A8 (settling) get cleaner implementations. **A1 is the highest-ROI change** — small surface, removes today's biggest internal coupling. **A8 and A9 are speculative** and tagged as such — included for completeness but expected to need iteration before implementation.

Items addressed in this document: **A1**, **A4**, **A6**, **A7**, **A8**, **A9** (Appendix A). A2 (re-frame-10x's public namespace) and A3 (re-com's `:src` in render trace) are covered in the companion docs for those libraries.

---

## A1. Documented `re-frame.introspect` public namespace

> **Status (2026-04-27).** **Open.** No `re-frame.introspect` namespace
> has shipped; re-frame-pair continues to reach into
> `re-frame.registrar/kind->id->handler`, `re-frame.subs/query->reaction`,
> and `re-frame.db/app-db` directly. The de-facto contract has held
> through every recent re-frame release the rig consumes.

**Spec proposal (verbatim from `docs/initial-spec.md` Appendix A):**

> Today re-frame-pair reaches into `re-frame.registrar/kind->id->handler`, `re-frame.subs/query->reaction`, and `re-frame.db/app-db` directly. These work but are not documented API. Promote a small surface — `(list-handlers kind)`, `(live-subs)`, `(current-app-db)`, `(registered? kind id)` — to a named public namespace so re-frame-pair (and future tooling) has a stable contract instead of a coupling to internal atoms.

### Why this matters

- **For re-frame-pair.** Removes direct atom coupling (`@registrar/kind->id->handler`, `@subs/query->reaction`) and pins the contract to a versioned public ns. Today these are de-facto-public — they work and they don't change often — but de-facto isn't a contract.
- **For re-frame.** Establishes a stable introspection surface. REPL-driven tools, debuggers, and skills like re-frame-pair stop having to read the source to know which atoms are safe to deref.

### Current state in re-frame

- **`re-frame.registrar/kind->id->handler`** at `/home/mike/code/re-frame/src/re_frame/registrar.cljc:15`:

  ```clojure
  ;; This atom contains a register of all handlers.
  ;; Contains a two layer map, keyed first by `kind` (of handler), and then `id` of handler.
  ;; Leaf nodes are handlers.
  (def kind->id->handler  (atom {}))
  ```
  Atom of `{kind {id handler-fn}}`. The `kinds` set is `#{:event :fx :cofx :sub :error}` (line 8). No metadata or ^:no-doc; it's "internal by convention".

- **`re-frame.subs/query->reaction`** at `/home/mike/code/re-frame/src/re_frame/subs.cljc:18`:

  ```clojure
  ;; De-duplicate subscriptions. If two or more equal subscriptions
  ;; are concurrently active, we want only one handler running.
  (def query->reaction (atom {}))
  ```
  Cache keyed by `[cache-key-map dyn-vec]` pairs (`cache-key` fn ~line 35). Not the plain query-vec; consumers that want a query-vec must extract `:re-frame/query-v` from the cache key.

- **`re-frame.db/app-db`** at `/home/mike/code/re-frame/src/re_frame/db.cljc:9`:

  ```clojure
  ;; Should not be accessed directly by application code.
  ;; Read access goes through subscriptions.
  ;; Updates via event handlers.
  (def app-db (ratom {}))
  ```
  Comment is explicit: app code shouldn't touch this directly. Tooling has no equivalent guidance.

- **Existing public surface that almost covers it.** `re-frame.registrar/get-handler` (line 17) is in a named ns (so reachable) but isn't marked stable, and there's no `list-handlers` or `registered?` companion. `re-frame.core/subscribe` activates a subscription; nothing returns the live set.

### v1 workaround in re-frame-pair

In `scripts/re_frame_pair/runtime.cljs`:

- **`registrar-describe`** (~line 101–149) reaches directly into `@registrar/kind->id->handler` — both for listing all kinds (line 125: `(for [[kind id->handler] @registrar/kind->id->handler] ...)`) and for retrieving a specific handler (line 132: `(get-in @registrar/kind->id->handler [kind id])`).
- **`subs-live`** / **`subs-sample`** (~line 175–185) deref `@subs/query->reaction` and extract `:re-frame/query-v` from each cache key (the keys are `[cache-key-map dyn-vec]` pairs, not raw query-vecs).
- **`snapshot`** (and friends) deref `re-frame.db/app-db` directly.

### Proposed change

A new namespace `re-frame.introspect` (alternatives: `re-frame.public`, or just expansion of `re-frame.core` with `^:since` markers — maintainer's call). Functions that wrap the internal atoms:

```clojure
(ns re-frame.introspect
  "Stable public surface for runtime introspection of re-frame state.
   Suitable for debuggers, REPL tools, and skills that need to read
   the registrar, subscription cache, or app-db without coupling to
   internal atoms.")

(defn list-handlers
  "Return a sorted vector of registered handler ids for `kind`.
   `kind` is one of #{:event :fx :cofx :sub :error}."
  [kind] ...)

(defn registered?
  "True if a handler is registered for [kind id]."
  [kind id] ...)

(defn live-subs
  "Vector of currently-cached subscription query vectors. Each entry
   is a query-v (e.g. [:my/sub 42]), de-duplicated. Order is unspecified."
  [] ...)

(defn current-app-db
  "Deref of re-frame's app-db. For read-only inspection; do not mutate."
  [] ...)
```

**Where each lives.** All four belong in `re-frame.introspect`. Internally, they deref the existing private atoms; externally, they are the documented boundary. The internal atoms remain private (the comments in `db.cljc` and `registrar.cljc` continue to apply to application code).

### Scope estimate

~30 LOC. One small namespace with four short fns and an ns docstring.

### Compatibility / migration notes

- **Strictly additive.** No collisions with `re-frame.core`; consumers still use `core` for everything else.
- **No coordination with re-frame-10x required.** 10x ships an inlined copy of re-frame; that copy will get the new ns automatically when 10x next bumps re-frame, but neither re-frame-pair nor re-frame depend on 10x for this.

### Open questions

- **Listing scope for `list-handlers`.** Should it include synthesised interceptor ids (`:db-handler`, `:fx-handler`)? Probably yes (consumers can filter). re-frame-pair's `registrar-describe` includes them.
- **Consider `(registered-kinds)`** to return the `kinds` set explicitly, instead of consumers having to know the literal set.
- **Stability marker.** `^:experimental` / `^:since "1.4"` / nothing? Recommendation: `^:since` once landed, no experimental marker — the surface is genuinely small and the implementations are trivial wrappers.

---

## A4. `register-epoch-cb` alongside `register-trace-cb`

> **Status (2026-04-27).** **Shipped upstream.** re-frame commit `4a53afb`
> (`rf-ybv` — register-epoch-cb / remove-epoch-cb / assemble-epochs)
> ships the epoch-granular callback this section proposed. Consumed in
> re-frame-pair via commit `9d4e948` (`rfp-zl8`) — `coerce-native-epoch`
> translates `assemble-epochs` output to the §4.3a record shape; the
> native epoch + trace ring buffers became the primary source for
> `epoch-by-id` / `last-epoch` / `last-claude-epoch`. The 10x epoch
> buffer is now a fallback for re-frame builds predating rf-ybv.
> Treat the proposal text below as historical record.

**Spec proposal (verbatim):**

> Today, 10x groups raw trace events into per-dispatch *epochs* inside itself. If re-frame emitted epoch-granular callbacks — one call per completed event carrying the assembled sub-runs, renders, effects map, and app-db before/after — 10x (and any future tooling) would simplify dramatically, and *epoch* would become a canonical re-frame concept rather than a 10x construct. Downstream benefit: re-frame-pair could read epochs from re-frame directly, shortening the dependency chain.

### Why this matters

- **For re-frame-pair.** Today, re-frame-pair reads epochs from 10x's inlined re-frame app-db at `[:epochs ...]`. With A4, re-frame-pair could subscribe directly and stop carrying the 10x-inlined-rf coupling. (10x would also benefit — it could thin its own metamorphic-parsing layer.)
- **For re-frame.** Promotes "epoch" — the unit of one dispatched event plus all its synchronous side effects — from a 10x-internal concept to a re-frame primitive. Other observability tools (error reporters, tracing frontends) get the same well-defined unit of work to hook on.

### Current state in re-frame

- **Existing trace machinery** in `/home/mike/code/re-frame/src/re_frame/trace.cljc`:
  - `register-trace-cb` (line 33) — registers a callback by name; receives raw trace events in batches, debounced via `debounce-time` (~line 76).
  - Trace event shape (line 47–53): `{:id :operation :op-type :tags :child-of :start}`, plus `:end` and `:duration` after `finish-trace` (line 100).
  - Trace op-types in flight: `:event`, `:sub/run`, `:sub/create`, `:render`, `:fx`, `:event/do-fx`, `:reagent/quiescent`, `:re-frame.router/fsm-trigger`, etc. See `re_frame/events.cljc` line ~57 (`(trace/with-trace {:operation event-id :op-type :event ...} ...)`).
  - **No epoch-granular callback.** re-frame emits raw traces; assembly into per-dispatch epochs is downstream consumers' problem.

- **10x's internal epoch concept** (the substrate re-frame-pair currently relies on) is built by 10x's metamorphic parser. From `runtime.cljs` notes: each epoch is `{:match-info <vec-of-raw-traces> :sub-state <map> :timing <map>}`, where `:match-info` covers one dispatch's full trace stream including the `:event` trace plus child `:sub/run`, `:render`, etc.

### v1 workaround in re-frame-pair

- **`read-10x-epochs`** (`runtime.cljs:302`) reads from 10x's inlined re-frame app-db. The data flow is: re-frame trace → `register-trace-cb` callback inside 10x → 10x's metamorphic parser groups them → 10x writes them to its inlined app-db → re-frame-pair derefs that.
- **`coerce-epoch`** (`runtime.cljs:510`) translates the 10x match record into re-frame-pair's §4.3a shape.
- The whole dependency chain is "re-frame trace → 10x → re-frame-pair". If re-frame emitted epochs natively, the chain becomes "re-frame epoch-cb → re-frame-pair" and 10x is no longer load-bearing for the trace substrate.

### Proposed change

A new callback registration in `re-frame.trace`:

```clojure
(defn register-epoch-cb
  "Register `f` to be called once per completed event with an
   assembled epoch record. Callback fires after the interceptor chain
   completes and effects have been applied."
  [key f] ...)
```

The callback is invoked once per completed event with an assembled epoch record. Suggested shape (close to re-frame-pair's §4.3a):

```clojure
{:id                <numeric trace id>     ;; matches the :event trace's :id
 :event             [<kw> ...]             ;; the dispatched event vector
 :app-db/before     {...}
 :app-db/after      {...}
 :coeffects         {...}
 :effects           {...}
 :interceptor-chain [<kw> ...]             ;; or full interceptor records — TBD
 :sub-runs          [<sub trace> ...]      ;; raw :sub/run traces in this epoch
 :sub-creates       [<sub trace> ...]
 :renders           [<render trace> ...]
 :timing            {:start <ms> :end <ms> :duration <ms>}}
```

**Mechanism.** The natural place to assemble an epoch is in `re_frame/router.cljc` after `events/handle` returns and `do-fx` has run — i.e. between the existing `:event` trace's `:start` and `:end`. The interceptor chain already exposes pre/post app-db (see `re_frame/events.cljc:49`'s `:app-db-before` / `:app-db-after` tags). Sub-runs and renders are assembled by collecting child traces of the `:event` trace's id (via `:child-of`).

**Delivery.** Like `register-trace-cb`, debounce delivery to keep callback latency off the dispatch hot path. (10x already does this; re-frame can do it once for everyone.)

**Gating.** Like `register-trace-cb`, gate on `re-frame.trace/trace-enabled?` (line 20) so production builds carry no overhead.

### Scope estimate

~100–150 LOC. Most complexity is in epoch assembly: walking the trace stream, partitioning by `:child-of`, and capturing pre/post app-db. The callback registry and delivery loop can closely mirror `register-trace-cb`.

### Compatibility / migration notes

- **Strictly additive.** Existing `register-trace-cb` consumers are unaffected.
- **Coordination opportunity with 10x.** If A4 lands, 10x could over time delegate epoch assembly to re-frame core and consume `register-epoch-cb` instead of building epochs from raw traces. Not required, but a natural follow-up.
- **Production overhead.** Same as `register-trace-cb` — none, when `trace-enabled?` is false (which is the default outside dev).

### Open questions

- **Cascades.** Does an epoch include cascaded `:dispatch` / `:dispatch-later` effects, or only the synchronous run of the originating event? Recommendation: only the synchronous run; cascaded events are separate epochs (and they get their own callback). This is how 10x already groups things.
- **Interceptor chain shape.** Just the ids, or full interceptor records (`{:id :before :after}` minus the fns)? Recommendation: just ids — fns aren't serialisable and consumers that want them can call `re-frame.introspect/list-handlers :event` (A1) to look them up.
- **Async settle.** Is there value in a separate `register-epoch-settled-cb` that fires after all cascaded dispatches resolve? Maybe — see A8 below, which is the same question with a different framing.
- **Where the assembly lives.** `re-frame.trace` (alongside `register-trace-cb`) or `re-frame.router`? Recommendation: `re-frame.trace` — same author, same delivery mechanism, same gating.

---

## A6. Dispatch provenance

> **Status (2026-04-27).** **Open.** No upstream dispatch-meta channel
> exists yet; re-frame-pair continues to tag its own dispatches via
> `tagged-dispatch-sync!` and the `current-who` ratom. Adjacent shipped
> work: rf-3p7 item 2 (`af024c3`) gives every dispatch an auto-generated
> `:dispatch-id`, which lets re-frame-pair correlate epochs to the
> originating dispatch without the before/after-id walking it used to
> need (consumed via `rfp-fxv`, commit `18a98db`) — but `:dispatch-id`
> doesn't carry caller-supplied provenance. The proposal below is
> still the cleanest upstream form.

### Why this matters

- **For re-frame-pair.** Today the skill maintains an out-of-band set of "Claude-originated" epoch ids and post-hoc filters by set membership. With provenance, the marker is in the epoch itself and post-hoc filtering becomes a structural query.
- **For re-frame.** Generic event annotation has uses well beyond skills: `{:from :user-click}` vs. `{:from :websocket-message}` vs. `{:from :test-harness}`. Tools, error reporters, and unit tests can all take advantage.

### Current state in re-frame

- **`re-frame.core/dispatch`** (line 23) and **`re-frame.core/dispatch-sync`** (line 43) take a single event vector. Both delegate to `re-frame.router` (`router.cljc:228`, `router.cljc:235`).
- The router enqueues / executes the event; metadata on the event vector (Clojure-style `^:meta`) does not survive the queue.
- **`re-frame.events/handle`** (`events.cljc:49`) extracts the event id from `(first-in-vector event-v)`, retrieves the interceptor chain, and runs it. The trace it emits captures `:event`, `:app-db-before`, `:app-db-after` — but no per-dispatch metadata channel.

### v1 workaround in re-frame-pair

- **`tagged-dispatch!`** / **`tagged-dispatch-sync!`** (`runtime.cljs:699–720`) pair a dispatch with a counter increment in app-db (`:claude-epoch-count` or similar). The increment becomes a tag on the resulting epoch (via `:effects` / `:coeffects` capture), and re-frame-pair tracks an out-of-band set of "Claude-originated" epoch ids in `claude-epoch-ids` (~line 690).
- Filtering: `last-claude-epoch` (line 760) checks `(contains? ours (:id raw))`.

This works but is fragile: it assumes the counter increment produces a distinguishable marker, and it pollutes app-db with a sentinel key.

### Proposed change

Either of two equivalent shapes:

**Option A — extend dispatch arity:**

```clojure
(defn dispatch
  ([event] ...)
  ([event meta-map]
   "Dispatch `event` annotated with `meta-map`. The metadata is
    threaded through to the epoch record (key :dispatch-meta) and to
    the raw :event trace's :tags (key :dispatch-meta)."))

(defn dispatch-sync
  ([event] ...)
  ([event meta-map] ...))
```

**Option B — preserve metadata on the event vector:**

```clojure
(dispatch ^{:from :re-frame-pair} [:my/event arg])
```

**Recommendation: Option A.** Cross-platform (CLJ vector metadata is awkward in CLJS), explicit, and easier to test.

**Threading.** Store `meta-map` as a key (e.g. `:dispatch-meta`) on the interceptor `:context` map during execution. Surface it on:

1. The `:event` trace's `:tags` (so `register-trace-cb` consumers see it).
2. The epoch record (key `:dispatch-meta`) if A4 lands.

### Scope estimate

~50 LOC across `re-frame.core` (new arity), `re-frame.router` (queue passes it through), and `re-frame.events` (handler exposes it on the trace's tags / epoch record).

### Compatibility / migration notes

- **Backward compatible.** Single-arity dispatch unchanged.
- **Most useful with A4.** Standalone, A6 just gets metadata onto the raw `:event` trace. With A4 epochs, it's a structural field on the epoch record.
- **No coordination with 10x required.** 10x's metamorphic parser already passes trace tags through; new keys ride along.

### Open questions

- **Re-dispatch behaviour.** If an interceptor (or `:dispatch` effect) re-dispatches, does the child inherit the parent's `:dispatch-meta`, or is it fresh? Recommendation: fresh. Inheritance is rarely what you want; consumers can pass it explicitly if they need it.
- **Schema for `meta-map`.** Free-form, or constrained (e.g. require `:from` to be a keyword)? Recommendation: free-form. The use cases haven't all been imagined.
- **Should `meta-map` be visible to interceptors?** I.e. can an interceptor read `(:dispatch-meta context)`? Yes — that's the simplest implementation, and it's sometimes useful (e.g. interceptor that logs only test-harness dispatches).

---

## A7. Retained handler source forms in dev builds

> **Status (2026-04-27).** **Superseded for the `handler/source` use
> case.** re-frame commit `15dfc25` (`rf-ysy`) ships a different mechanism
> than this section proposed: instead of retaining source *forms*, the
> upstream `reg-event-db` / `reg-event-fx` / `reg-event-ctx` / `reg-sub`
> / `reg-fx` macros now `with-meta` `{:file :line}` onto the registered
> handler value at expansion time. `(meta (registrar/get-handler kind id))`
> returns the call-site location — sufficient for the SKILL.md "Where
> in the code is this handler?" recipe. Consumed in re-frame-pair via
> `rfp-hpu` (commit `fd74b8f`), which retired the briefly-shipped local
> `rfp-rsg` side-table. The "show the source form itself" use case A7
> originally proposed remains open — useful for "explain this handler"
> recipes that go beyond the file/line jump — but is lower priority now
> that the location lookup is solved.

### Why this matters

- **For re-frame-pair.** Today `registrar-describe` returns `:source :not-retained` for every handler. Recipes like "explain what this handler does" or "show me before I overwrite it" cannot land. A retained source form unblocks them.
- **For re-frame.** REPL-driven development becomes richer — `(some-introspector :event :my/event)` can show the actual code, not just "the handler is some opaque fn".

### Current state in re-frame

- `register-handler` in `/home/mike/code/re-frame/src/re_frame/registrar.cljc:33` stores the compiled fn directly:
  ```clojure
  (swap! kind->id->handler assoc-in [kind id] handler-fn)
  ```
- ClojureScript: the source form is not retained on the fn unless explicitly captured. `(meta f)` is generally `nil` after compilation.
- Clojure: `(meta v)` on a var includes `:file :line :column :doc`, but registered handlers are values, not vars — same problem.
- **Dev gating exists.** `re-frame.interop/debug-enabled?` (referenced at `registrar.cljc:5,35`) is the goog-define / `:closure-defines` gate used elsewhere in re-frame.

### v1 workaround in re-frame-pair

- `registrar-describe` (`runtime.cljs:101–149`) returns `:source :not-retained` because the registrar discards the form on store. Recipes that want source content punt to the user's editor.

### Proposed change

Capture source forms at the `reg-event-db` / `reg-event-fx` / `reg-sub` / `reg-fx` macro-call layer (where the source is a literal in the user's code), and store them alongside the handler fn — guarded by `debug-enabled?` so production builds carry zero overhead.

**Storage shape.** Two options:

- **Option 1: as metadata on the fn.** `(vary-meta handler-fn assoc :source-form '(fn [db ev] ...))`. Backward-compatible — `get-handler` returns the fn as today, and consumers that want source call `(:source-form (meta f))`.
- **Option 2: as a wrapper map.** Store `{:fn handler-fn :source-form ...}`. `get-handler` unwraps for callers, with a new sibling like `(get-handler-source kind id)` to expose the form.

**Recommendation: Option 1.** Less invasive; everything that holds a handler reference today still gets the same value back.

**Capture point.** At the `reg-event-*` / `reg-sub` / `reg-fx` macros, before macroexpansion. The form is the user's literal code — not the macroexpanded handler — which is more readable.

**Production gating.** `(when (interop/debug-enabled?) ...)` around the metadata attachment. Production builds get the fn unchanged.

### Scope estimate

~30 LOC across the `reg-*` macros and a small unwrap in `registrar/get-handler` (only if Option 2 is taken).

### Compatibility / migration notes

- **Backward compatible** under Option 1 (metadata-only).
- **Dev-only.** Production builds carry no extra memory or attribute lookups.
- **Does not affect 10x.** 10x's inlined re-frame would benefit independently when it picks up the change.

### Open questions

- **Form shape.** Whole `(reg-event-db :id (fn [db ev] ...))` call, or just the inner handler form? Recommendation: just the handler form (the `(fn [...] ...)` body), so consumers can show it without re-frame boilerplate.
- **Memory.** Source forms are dev-only, but they're not free. Should there be a `re-frame.config/retain-source?` knob? Recommendation: not yet — wait for someone to hit a memory ceiling. The whole feature is gated on `debug-enabled?` already.
- **Spec / malli forms.** If a sub uses a spec or malli for arg validation, should that form also be retained? Probably out-of-scope for A7; revisit if anyone asks.

---

## A8. `dispatch-and-settle` returning a promise/channel

> **Status (2026-04-27).** **Shipped upstream.** re-frame commit `f8f0f59`
> (`rf-4mr` — `dispatch-and-settle`) ships the fire-and-await primitive
> this section proposed. The settle scope is the cascade of
> `:fx [:dispatch ...]` children with an adaptive quiet-period heuristic,
> bypassing the "settle definition" open question by deferring to the
> caller's quiet-period budget. Consumed in re-frame-pair via
> `rfp-4ew` (commit `c87529c`) — `dispatch.sh --trace` now uses
> `dispatch-and-settle` with a runtime wrapper that stores the
> resolution in a session-local atom (Promise can't round-trip
> cljs-eval) and reconstitutes the settled record from the native
> epoch buffer. Falls back to fixed-sleep + `tagged-dispatch-sync!`
> for re-frame builds predating rf-4mr. Treat the proposal text below
> as historical record.

### Why this matters

- **For re-frame-pair.** Today, the skill uses a fixed wait (`trace-debounce-settle-ms` ~80ms + one animation frame) after a dispatch before reading the resulting epoch. If a cascaded `:dispatch` lands later, it's missed by the first read. A8 collapses this into a single primitive that the caller awaits.
- **For re-frame.** Tests that exercise dispatch chains gain `(<! (dispatch-and-settle [:my/event]))` or `@(dispatch-and-settle [:my/event])` — no more polling.

### Current state in re-frame

- **`:dispatch`** (`fx.cljc:131–137`) enqueues an event. Returns immediately; the event runs on a later tick.
- **`:dispatch-later`** (`fx.cljc:84–96`) wraps `set-timeout!`. Returns immediately.
- **`:dispatch-n`** (`fx.cljc:151–157`) loops over events, all enqueued. Returns immediately.
- **No settlement primitive.** Once `do-fx` (`fx.cljc:47–65`, the `:after` interceptor) runs the effects, the event handler is "done" by re-frame's bookkeeping, even if cascaded dispatches haven't run yet.

### v1 workaround in re-frame-pair

- **`dispatch-and-collect`** (`runtime.cljs:773–802`): `dispatch-sync` followed by an ~80ms debounce wait plus one animation frame, then read the epoch buffer. Doesn't wait for cascaded dispatches.
- **`dispatch-and-settle-after`** (the babashka-side waiter, ~`runtime.cljs:805+`): operator-side timeout + poll loop that retries reading the epoch until it appears or a budget runs out.

### Proposed change

A new fn: `(dispatch-and-settle event opts)`. Returns a Promise (CLJS) or core.async channel (Clojure / configurable).

```clojure
(defn dispatch-and-settle
  "Dispatch `event` and return a promise that resolves when the event
   and its (configurable) cascade of effects complete.

   opts (all optional):
     :settle-on            #{fx-id ...}   ;; fx ids to wait on; default #{:db :dispatch :dispatch-n}
     :timeout-ms           int            ;; max wait; default 5000
     :include-cascaded?    bool           ;; if true, return the cascaded epochs too; default false

   Resolves to:
     {:ok? true :root-epoch <epoch> [:cascaded-epochs [...]]}
   or
     {:ok? false :reason :timeout|:handler-threw|:no-new-epoch :event <ev>}"
  [event opts] ...)
```

**Implementation sketch.** Hook into the effect-handler layer to track which effects fire during this dispatch (and its `:dispatch` cascade). Once the "settle set" has all completed (or timeout), resolve the promise. If A4 is in flight, the implementation can use the epoch callback to detect completion; without A4 it has to instrument the fx handlers directly.

### Scope estimate

~150–200 LOC. Most complexity is the settlement tracker and the cascade-tracking logic. The promise / channel return is straightforward.

### Compatibility / migration notes

- **Strictly additive.** No effect on existing dispatch.
- **Cleaner with A4.** Without A4, A8 needs to instrument the fx layer; with A4, completion detection rides on the epoch callback.
- **Async effects.** Effects like `:http-xhrio` need cooperation: their handlers must signal completion (return a promise, fire a callback, …). Recommendation: start with synchronous fx handlers in `:settle-on`; provide an extension point for app-defined async fx.

### Open questions

- **Settle definition.** Is "settled" just "all `:settle-on` fx fired and returned"? What about `:http-xhrio` requests still in flight? (Recommendation: the default `:settle-on` is sync-only; apps opt in to broader settling.)
- **Cascade depth.** Wait for one level of `:dispatch` cascade, or recursively? (Recommendation: one level. Apps that want deep settling can call `dispatch-and-settle` recursively.)
- **Promise vs. channel.** Promise is simpler in CLJS; core.async is more idiomatic in CLJ. Recommendation: return a Promise in CLJS, expose a separate `dispatch-and-settle-chan` in CLJ if anyone needs it.
- **Speculative tier.** A8 is genuinely hard to make general. Worth a spike before committing to a public API.

---

## A9. Effect substitution for probe dispatches

> **Status (2026-04-27).** **Shipped upstream.** re-frame commit `2651a30`
> (`rf-ge8` — `dispatch-with` / `dispatch-sync-with`) ships per-dispatch
> fx-handler substitution via `:re-frame/fx-overrides` event-meta. No
> global state to restore — the override expires when the cascade
> finishes. Consumed in re-frame-pair via `rfp-zml` (commit `69c570d`):
> `dispatch.sh --stub :http-xhrio --stub :navigate` builds the
> `{fx-id record-only-stub}` map for the common probe case;
> `stubbed-effects-since` exposes the captured-effect log; SKILL.md's
> experiment-loop recipe gained a "side-effecting handlers" subsection.
> Treat the proposal text below as historical record.

### Why this matters

- **For re-frame-pair.** Enables "dry-run" dispatches: fire an event, observe the resulting `:effects` and app-db change, but stub out side effects (HTTP, navigation, local-storage) so probing is safe in dev. Today there's no way for the skill to do this without modifying app code.
- **For re-frame.** Generalises the test-mode machinery. Today, isolating a dispatch from real fx means writing a manual `reg-fx` override before the dispatch and restoring after. A primitive collapses that pattern.

### Current state in re-frame

- **Test utilities** in `re-frame.core` (`core.cljc`):
  - `make-restore-fn` (line 1141) — checkpoints handlers + app-db, returns a restore fn.
  - `purge-event-queue` (line 1169) — clears queued events.
  - `add-post-event-callback` / `remove-post-event-callback` (lines 1177, 1201) — post-event hooks.
  - **No effect substitution.** Tests that need to stub effects do it manually with `reg-fx` overrides.
- **`reg-fx`** (`fx.cljc:17`) registers a handler; storage is via the registrar atom. No "stub" or "double" notion.
- **Interceptor execution** in `re_frame/interceptor.cljc` runs effects via `do-fx` (`fx.cljc:47`), which retrieves handlers via `(get-handler kind effect-key false)` and calls them. There's no per-dispatch override channel.

### v1 workaround in re-frame-pair

None. The skill accepts that dispatches have real side effects today. A9 is "would be nice"; runtime.cljs has no equivalent feature.

### Proposed change

`(dispatch-with event {fx-id stub-fn ...})` — dispatch an event with selected fx handlers temporarily substituted for the duration of that dispatch (and any cascade).

```clojure
(dispatch-with [:user/login {:email "..." :password "..."}]
               {:http-xhrio (fn [req]
                              (js/console.log "stubbed HTTP" req))})
```

**Implementation, two approaches:**

- **Approach 1: Save / replace / restore.** Capture current handlers; `reg-fx` the stubs; dispatch; `reg-fx` the originals back. Simple (~50 LOC) but globally mutates the registrar — hazardous if two `dispatch-with` calls overlap.
- **Approach 2: Context-scoped substitution.** Thread an `:fx-overrides` map through the interceptor `:context`; `do-fx` checks for an override before falling back to the registered handler. ~100–150 LOC, but pure (no global mutation, safe under cascades and concurrency).

**Recommendation: Approach 2.** Approach 1 is a footgun; Approach 2 is the right shape and reuses the existing context channel.

### Scope estimate

~50–150 LOC depending on approach. Approach 2 (recommended) is ~100–150 LOC across `re-frame.fx` (override-aware `do-fx`) and `re-frame.core` (the new `dispatch-with` arity).

### Compatibility / migration notes

- **Strictly additive.** Existing dispatches and `reg-fx` handlers unchanged.
- **No coordination with 10x.** Trace events still emit; the "stubbed" effect just ran a different fn.
- **Speculative tier.** Same caveat as A8 — the design probably needs a spike (and integration with real test-style stubbing) before committing.

### Open questions

- **Cascade scope.** Should overrides apply to cascaded `:dispatch`/`:dispatch-later` events? Recommendation: yes — the override is a property of the substitution context, and substitutions naturally extend to children.
- **Async effects.** A stub for `:http-xhrio` needs to satisfy the same return contract as the real handler (return a promise / fire an on-success callback). Worth documenting in the public API: "your stub fn should mirror the contract of the original."
- **Integration with `re-frame.test`.** Should `dispatch-with` be in `re-frame.core` or a separate `re-frame.test` ns? Recommendation: `re-frame.core` — it's a runtime capability, not test-only. The test utilities can use it, but it's not gated on a test environment.
- **Restore guarantees.** Approach 1 requires a `try/finally` to be safe. Approach 2 sidesteps the question — there's no global state to restore.
