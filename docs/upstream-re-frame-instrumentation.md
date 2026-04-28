# Upstream re-frame instrumentation — gaps from a re-frame-pair view

> **Status (2026-04-28): largely SUPERSEDED.** re-frame now exposes
> a single `re-frame.tooling` re-export namespace covering the §3.x
> tooling surface (dispatch overrides, trace callbacks, schema
> validation, live subscription cache, registrar, version). Tooling
> consumers can `(:require [re-frame.tooling :as rft])` to discover
> the supported surface, instead of grepping across `re-frame.core`
> / `re-frame.trace` / `re-frame.subs` / `re-frame.registrar`. This
> doc remains as design archaeology — the gaps below motivated the
> primitives that `re-frame.tooling` re-exports today; per-section
> Status annotations record where each landed.

A survey of where re-frame's existing instrumentation surface (the
`re-frame.trace` channel, the registrar, the version surface) leaves
re-frame-pair reaching into private shapes or graceful-failing — and
what re-frame *itself* could grow that would close the gap, even where
re-frame-10x has no immediate use for it.

> **Scope.** Upstream `re-frame/re-frame` only. We do **not** propose
> re-frame-pair-side workarounds, re-frame-debux additions covered by
> the runtime API just shipped at `~/code/re-frame-debux` commit
> `4ed07c9` (`wrap-handler!` / `unwrap-handler!`; see §4), or
> reagent / re-com / shadow-cljs changes. Each gap cites where
> re-frame-pair touches a non-public shape today and sketches a
> concrete upstream primitive that would replace the workaround.

> **Implementation status (2026-04-27).** Eight of nine gaps have
> shipped upstream: §3.1 (rf-3p7 item 1, `24c26df`), §3.2 (rf-3p7
> item 3 `fa90f70` + rf-cna `9e1fbeb`), §3.3 (`8cc973c`), §3.4
> (rf-3p7 item 2 `af024c3`), §3.5 (rf-ysy `15dfc25`), §3.6
> (`75746fc`), §3.7 (rf-gta `968cecf`), and §3.8 (rf-yyo
> `84d3bf0`). §3.1 through §3.7 are consumed in re-frame-pair;
> §3.8 consumer-side adoption remains TODO. §3.9 is out of scope by
> construction. See per-section Status annotations below; the prose
> under each remains as the proposal that motivated the upstream
> change. Companion arc: rf-hsl
> (`c40cb82`), rf-ybv (`4a53afb`), rf-ge8 (`2651a30`), rf-4mr
> (`f8f0f59`), and rf-556 (`b68318b`) shipped during the same window
> but address surfaces this doc didn't enumerate — see
> `docs/inspirations-debux.md` and `STATUS.md` for those.

## 1. What re-frame currently emits

**a) The trace channel.** `re-frame.trace` (`trace.cljc`) is the only
structured event channel. `with-trace` (lines 109-121) and
`finish-trace` (100-107) push records into a `(defonce traces …)`
atom; `register-trace-cb` (33-39) lets consumers subscribe to a 50 ms
debounced batch. Records have a fixed top-level shape — `{:id
:operation :op-type :tags :child-of :start :duration :end}` — but
`:tags` contents are free-form, written via `merge-trace!`
(123-129) at every interesting point: event boundary
(`std_interceptors.cljc:99,129,151` — `:effects` `:coeffects`),
interceptor chain (`interceptor.cljc:234` — `:interceptors`),
app-db before/after (`events.cljc:60,62`), sub creates / runs /
disposes (`subs.cljc:66,108,114,131,136,185,264,280`). The
namespace docstring (`trace.cljc:1-2`) declares the whole channel
"`Alpha quality, subject to change/break at any time.`" — that
caveat IS the structural problem this document is about.

**b) The registrar.** `re-frame.registrar/kind->id->handler`
(`registrar.cljc:15`) is a public atom of `{kind {id handler}}`.
For `:event`, the stored value is the interceptor *chain*, with the
user's handler captured in the closure of a `:db-handler` /
`:fx-handler` / `:ctx-handler` terminal interceptor
(`std_interceptors.cljc:73-154`). For `:sub` `:fx` `:cofx`, the
stored value is the user's plain fn.

**c) Versions.** None at runtime. `deps.edn` carries a build-side
version (`day8.dev/git-app-version!` macro, `re-frame/deps.edn:36`),
but no `re-frame.core/version` constant or `goog-define` is exposed
in-browser. re-frame-10x is the same — confirmed by grep.

## 2. What re-frame-pair extracts today

`scripts/re_frame_pair/runtime.cljs` reads from those surfaces for
every op. Key call sites where the walk reaches into shapes that
aren't a published API:

- `coerce-epoch` (`runtime.cljs:557-615`) pulls `:event :coeffects
  :effects :interceptors :app-db-before :app-db-after :code` from
  the `:event` trace's `:tags`. Every key is a `merge-trace!` call
  site in re-frame's source — accessed by structural walk, not via
  a named accessor.
- `extract-query-vs` (`runtime.cljs:164-173`) reaches into
  `re-frame.subs/query->reaction` (`subs.cljc:18`) and pulls
  `:re-frame/query-v` out of cache-key maps (shape defined at
  `subs.cljc:35-44`).
- `registrar-describe` (`runtime.cljs:101-149`) walks the
  interceptor chain by `:id` keyword and switches on `:db-handler` /
  `:fx-handler` / `:ctx-handler` — keyword names not documented
  outside `std_interceptors.cljc:87,119,143`.
- `handler-source` (`runtime.cljs:190-235`) calls `(meta f)` and
  reliably returns `:no-source-meta` (see
  `docs/handler-source-meta.md`).
- `read-version-of` (`runtime.cljs:1466-1486`) returns `:unknown`
  for `:re-frame` and `:re-frame-10x`. `version-floors`
  (`:1455-1464`) keeps placeholders nil for this reason.
- `subs-live` plus the `:subs/ran` reverse-engineering at
  `runtime.cljs:445-477` infer the subscription dependency graph from
  `:sub/run` execution order. re-frame's trace records each run
  (`subs.cljc:255-265`) but never emits parent → child signal edges.
- `tagged-dispatch-sync!` (`:885-941`) plus `claude-epoch-ids`
  (`:853-854`) and `collect-after-dispatch` (`:991-1039`) exist
  because there is no built-in way to attribute "I dispatched THIS
  event, please tag the resulting epoch."
- `inlined-rf-known-version-paths` (`:266-273`) hard-codes 10x's
  inlined-re-frame slug `["v1v3v0"]` as a fallback enumeration.

Each is honestly named in the source (search for "TODO verify",
":no-source-meta", ":unknown", ":not-retained") — the graceful-fail
shapes ARE the documentation that re-frame's instrumentation falls
short.

## 3. The gaps

Each gap is named so a re-frame contributor could take it directly
into an issue. Severity is leverage-for-re-frame-pair: H = blocking a
recipe today, M = workaround that works, L = documentation-shaped.
10x impact is called out honestly — most are re-frame-pair-only
beneficiaries.

### 3.1. (H) No published trace-tag schema

> **Status (2026-04-27).** Shipped upstream as `re-frame.trace/tag-schema`
> via rf-3p7 item 1 (commit `24c26df`). Consumed in re-frame-pair via
> `rfp-fxv` (commit `18a98db`), which collapsed the hand-rolled tag walks
> onto the published contract. The text below is retained as the proposal
> that motivated the change.

**Workaround.** Read every key out of `:tags` by walking the
`merge-trace!` call sites. `coerce-epoch` documents in a comment
which key it expects per `:op-type`; a tag rename in re-frame would
silently break the read with no compile-time signal.

**Upstream addition.** A spec / reference document of the trace tag
shape per `:op-type`. Two flavours: (a) **doc-only** —
`re-frame.trace/tag-schema` constant (`op-type` →
`{:tags-required #{...} :tags-optional #{...} :doc "..."}`) plus a
`docs/trace-tags.md` page; or (b) **spec-on-emit** — opt-in
`re-frame.trace.spec` ns gated by a dev-only `validate-trace?` flag
that asserts the shape on `merge-trace!`. Source of truth: the
existing `merge-trace!` call sites; 10x's
`tools/metamorphic.cljc:130-202` already encodes most of this as
predicates. **10x impact:** indirect — 10x lives downstream of the
same shape. Most load-bearing for third-party consumers
(re-frame-pair, custom devtools).

### 3.2. (H) No subscription dependency graph signal

> **Status (2026-04-27).** Shipped upstream as `:input-query-vs` on
> `:sub/run` traces (rf-3p7 item 3, commit `fa90f70`), with call-site
> meta on each query-v added by rf-cna (commit `9e1fbeb`). Consumed in
> re-frame-pair via `rfp-fxv` (commit `18a98db`) — drops the dep-graph
> reverse-engineering — and `rfp-283` (commit `1038fcf`) — surfaces
> `:subscribe/source` and `:input-query-sources` on each `:subs/ran`
> entry of the coerced epoch. The text below is retained as the
> proposal that motivated the change.

**Workaround.** Reconstruct parent → child edges from `:sub/run`
trace order (`runtime.cljs:445-477`). `:input-signals` IS emitted
(`subs.cljc:185`) but only as `reagent-id`s — not the query-vectors
a human or LLM can consume.

**Upstream addition.** Promote the existing `:input-signals` walk to
a parallel `:input-query-vs` tag — query-vector shape, on the same
hot path. Either push `{:re-frame/query-v query-v}` onto the
reaction's metadata at `cache-and-return` (`subs.cljc:46-67`) and
read it back in `deref-input-signals`, or look up each signal's
`reagent-id` against `query->reaction`. ~10 LOC. **10x impact:**
possible — 10x's "Subs" panel could draw a graph but doesn't today.
**re-frame-pair needs this** for the "why did X re-render?" recipe.

### 3.3. (M) `read-version-of` returns `:unknown` for re-frame and re-frame-10x

> **Status (2026-04-27).** Shipped upstream — re-frame commit `8cc973c`
> ("public version constant for runtime version detection") exposes
> `re-frame.core/version` at runtime. `version-floors` enforcement on
> the re-frame-pair side can now activate; pair with the identical fix
> in re-frame-10x. The text below is retained as the proposal.

**Workaround.** `runtime.cljs:1466-1486` falls back to `:unknown`;
`version-report` admits `:enforcement-live? false`.

**Upstream addition.** A runtime-readable version constant —
`(def version "x.y.z")` populated by the existing
`day8.dev/git-app-version!` macro at compile time, or a
`goog-define`. ~5 LOC. re-com already does this
(`re-com.config/version`, the only readable runtime version in the
day8 stack — `runtime.cljs:1480-1481`). **10x impact:** none, but
10x is a candidate for the same fix; ship in lockstep.

### 3.4. (H) No way to identify "the user-fired event" vs "events fired as effects"

> **Status (2026-04-27).** Shipped upstream as auto-generated
> `:dispatch-id` on every `dispatch` / `dispatch-sync`, threaded through
> `re-frame.router` and emitted on event traces (rf-3p7 item 2, commit
> `af024c3`). Consumed in re-frame-pair via `rfp-fxv` (commit `18a98db`)
> — collapses the ~80 LOC of `claude-epoch-ids` / `collect-after-
> dispatch` workaround into a one-line `(recent-dispatch-id)` read of
> the most-recent `:event` trace's `:dispatch-id`. Cross-references
> §3.7 (which addresses the orthogonal "preserve original event vs
> trim-v / unwrap" concern via `:event/original`). The text below is
> retained as the proposal.

**Workaround.** `tagged-dispatch!` / `tagged-dispatch-sync!`
(`runtime.cljs:863-941`) bookend dispatches with `:claude` /
`:app` tagging and remember resulting epoch ids in
`claude-epoch-ids`; `collect-after-dispatch` walks `before-id` →
first new match (`:1019-1039`). Roughly 80 LOC of workaround,
existing because re-frame's trace doesn't propagate "who dispatched
this".

**Upstream addition.** Auto-generate a `:dispatch-id` (counter) on
every `dispatch` / `dispatch-sync`, written into the event trace's
`:tags`. Chained `:fx [:dispatch …]` inherits parent's id (so
consumers can ask "what was the user-originated event for this
chain?"). Implementation: thread a dynamic `*current-dispatch-tag*`
through `re-frame.router/dispatch` / `dispatch-sync`, merge into
`events.cljc:handle`'s `:tags`. ~20 LOC. **10x impact:** modest —
10x's epoch UI could colour-code agent-dispatched vs user-clicked.
**Big win for re-frame-pair** — collapses
`runtime.cljs:750-857` into a one-line filter.

### 3.5. (M) `handler/source` returns `:no-source-meta` because `(meta f)` is hidden

> **Status (2026-04-27).** Shipped upstream via a different mechanism
> than the side-table this doc sketched: re-frame's `reg-event-db` /
> `reg-event-fx` / `reg-event-ctx` / `reg-sub` / `reg-fx` are now
> defmacros that capture `*file*` + `(:line (meta &form))` at
> expansion time and `with-meta` `{:file :line}` onto the registered
> value (rf-ysy, commit `15dfc25`). For `:event` the meta lives on the
> interceptor *vector* (vectors carry meta natively); for `:sub` /
> `:fx` / `:cofx` it lives on the fn (CLJS `IObj` on `cljs.core/fn`,
> CLJ `MetaFn`). `(meta (registrar/get-handler kind id))` returns
> `{:file :line}` directly. Consumed in re-frame-pair via `rfp-hpu`
> (commit `fd74b8f`), which retired the briefly-shipped `rfp-rsg`
> side-table — see also `docs/handler-source-meta.md`'s status banner.
> The Sketch below is wrong about implementation (no side-table)
> but right about effect.

**Workaround.** Documented in `docs/handler-source-meta.md`. The
structural cause is `db-handler->interceptor`
(`std_interceptors.cljc:73-102`) capturing the user's fn in the
closure of `db-handler-before`. Path 3 in that doc proposes a
re-frame-pair-side macro (`rfp-rsg`); §3.5 here proposes the
*upstream* form, which is strictly better (see §4.2).

**Upstream addition.** A side-table at `re-frame.core/handler-meta`
populated at `reg-event-*` / `reg-sub` / `reg-fx` time, capturing
`(meta &form)` plus `(meta handler-fn)` keyed by `[kind id]`, with
a public `(handler-meta kind id)` accessor. ~30 LOC. Sketch:

```
(defonce handler-meta (atom {}))

(defmacro reg-event-db
  [id & args]
  (let [meta-here (select-keys (meta &form) [:file :line :column])]
    `(do (events/register ~id [...])  ;; existing chain build
         (swap! re-frame.core/handler-meta assoc [:event ~id]
                (merge ~meta-here (meta ~(last args)))))))
```

`re-frame-pair.runtime/handler-source` reads `(get @rf/handler-meta
[kind id])` — no closure introspection, no shadow-cljs source-map
dance. **10x impact:** yes — 10x's "Event" panel could surface the
`reg-event-db` source location. Mutual win.

### 3.6. (M) Registrar overwrite is silent in production builds

> **Status (2026-04-27).** Shipped upstream as re-frame commit `75746fc`
> ("loud-fail on registrar overwrite in production"). re-frame-pair has
> no consumer-side change to make — the upstream signal is now
> unconditional. The text below is retained as the proposal.

**Workaround.** None — re-frame-pair re-registers via
`reg-event-db` etc. and trusts the warning at
`registrar.cljc:33-39`, which is gated on `debug-enabled?`. In
advanced-compiled production builds the swaps are silent.
Acceptable for dev — a real gap for an LLM driving a production app
via the REPL (a use case SKILL.md's *Cardinal rule* admits).

**Upstream addition.** Emit a `:re-frame/handler-overwritten` (or
`:registrar/overwrite`) trace event on re-registration, regardless
of `debug-enabled?`. Carries `:kind :id` tags. ~5 LOC inside
`register-handler`. **10x impact:** could badge "swapped this
session" on its panels; doesn't today.

### 3.7. (L) Trace tag for the original (pre-interceptor) event vector

> **Status (2026-04-27).** Shipped upstream as `:event/original` on the
> event trace's `:tags`, pinned at handle entry before any interceptor
> runs (rf-gta, commit `968cecf`). `find-where` predicates against the
> *original* event vector are now stable for `ctx-handler` users too —
> the previous accident-of-symmetry caveat is gone. The text below is
> retained as the proposal.

**Workaround.** `:event` in the trace tags is the event that ran
the handler, but `unwrap` and `trim-v`
(`std_interceptors.cljc:38-66`) mutate the event seen by the
handler. The original is preserved in `:original-event` on the
context (`interceptor.cljc:138`) but never written into the trace.
re-frame-pair's `find-where` predicates that match on the
unsubscripted event id work because of an accident — the
explicit `:event` tag happens to be the original event vector for
`db-handler` and `fx-handler`. For `ctx-handler` users this is
potentially surprising.

**Upstream addition.** Add `:original-event` to the event trace's
`:tags` next to `:event` (~3 LOC at `events.cljc:57-62`). No-op for
users who don't `unwrap`/`trim-v`; load-bearing for those who do.
**10x impact:** marginal annotation.

### 3.8. (L) No reaction → query-v reverse map at runtime

> **Status (2026-04-28).** Shipped upstream as
> `re-frame.subs/query-v-for-reaction` and
> `re-frame.subs/live-query-vs`. Both re-exported at
> `re-frame.core/<sym>` and surfaced on `re-frame.tooling/<sym>` for
> tooling consumers (rf-yyo `84d3bf0` + the live-query-vs follow-up).
> An object-identity-keyed reverse map is maintained alongside
> `query->reaction`; entries are inserted on `cache-and-return` and
> removed on dispose. Consumer-side adoption is still TODO:
> re-frame-pair currently retains `extract-query-vs` over
> `query->reaction` cache keys. The text below is retained as the
> proposal that motivated the change.

**Workaround.** `extract-query-vs` (`runtime.cljs:164-173`) walks
`query->reaction`'s keys to recover query-vectors. The keys are
`[cache-key-map dyn-vec]` pairs — public, but the shape isn't a
documented contract.

**Upstream addition.** `(re-frame.subs/live-query-vs)` — return
`(keep #(:re-frame/query-v (first %)) (keys @query->reaction))`.
Three lines, makes the contract reader-friendly, survives cache-key
refactors. **10x impact:** none — 10x reads the reactions, not the
keys. **re-frame-pair only.**

### 3.9. Out of scope — `inlined-rf-version-paths`

`runtime.cljs:266-273` enumerates 10x's inlined-re-frame namespace
slug. This is a re-frame-10x packaging detail (10x bundles its own
copy of re-frame). **Not a re-frame upstream gap** — listed only so
it's not double-counted.

## 4. Already covered or out of scope

### 4.1. Just shipped in re-frame-debux — don't propose at re-frame layer

Commit `4ed07c9` in `~/code/re-frame-debux` ships
`day8.re-frame.tracing.runtime` (205 LOC at
`/home/mike/code/re-frame-debux/src/day8/re_frame/tracing/runtime.cljc`).
Public API: `wrap-handler!` / `wrap-event-fx!` / `wrap-event-ctx!`
/ `wrap-sub!` / `wrap-fx!`, `unwrap-handler!` / `unwrap-sub!` /
`unwrap-fx!` / `unwrap-all!`, plus `wrapped?` / `wrapped-list`. It
uses the public `re-frame.registrar/register-handler` and a
side-table of `[kind id] → original-handler`.

**Should re-frame grow a parallel primitive?** Probably not. Debux's
`wrap-handler!` is *trace-instrumented* wrapping (`fn-traced` macro
at expansion time). A re-frame-side `(replace-handler! kind id
new-fn)` with built-in restore would be uninstrumented hot-swap —
strictly weaker, and not *instrumentation* per this document's
scope. Verdict: out of scope.

### 4.2. Should stay re-frame-pair-side

> **Status (2026-04-27).** §3.5 was the better upstream form — it
> shipped as rf-ysy (commit `15dfc25`). `rfp-rsg` shipped briefly
> (commit `0520a44`) and was retired by `rfp-hpu` (commit `fd74b8f`)
> once rf-ysy made the local side-table redundant. `runtime.clj` was
> deleted; `handler-source` simplified to a single `(meta stored)`
> read. The discussion below is retained as the rationale for picking
> the upstream path.

The v0.2 `rfp-rsg` macro
(`re-frame-pair.runtime/reg-event-db` capturing call-site meta into
a side-table) is queued in re-frame-pair's `STATUS.md` *v0.2 /
deferred backlog*. It **could** be upstream-ed as part of §3.5, but
isn't strictly necessary as an upstream contribution: the
user-facing ergonomics are nearly identical whether the macro lives
in re-frame or re-frame-pair. Reasons to keep it re-frame-pair-side:
it's an opt-in (users who don't run re-frame-pair get no benefit);
the read target is re-frame-pair's concern; a small macro shim is
cheaper to maintain than a new public re-frame surface. **§3.5 is
the better upstream form** because it benefits 10x and any
third-party tool, not just re-frame-pair. Ship that, drop
`rfp-rsg`.

### 4.3. Not addressed here

- **Schema for app-db / coeffects / effects.** App-db is the user's
  data, not re-frame's.
- **A second `register-trace-cb`.** re-frame-pair commits explicitly
  to *not* installing one (read from 10x's epoch buffer; spec §3.2).
- **Trace stream persistence / replay.** Distinct from instrumentation.
- **Reagent-side render trace shape.** Reagent, not re-frame.

## 5. Top 5 actionable takeaways (ranked by leverage)

All five top-leverage takeaways have landed upstream; see the
per-section Status banners for commit references and consumer status.
This list is retained as a historical record of the implementation
order.

1. **(§3.1) Spec the trace-tag schema.** *"re-frame's trace channel
   is the only structured event surface, and consumers (10x, debux,
   re-frame-pair) read free-form `:tags` keys whose names and shapes
   are documented only by reading the `merge-trace!` call sites in
   re-frame's source. Promote the existing implicit schema (per
   `:op-type`) into a named spec — either a doc-only constant
   (`re-frame.trace/tag-schema`) or an opt-in `re-frame.trace.spec`
   namespace gated by a dev-only `validate-trace?` flag. Source of
   truth: the `merge-trace!` call sites at `events.cljc:60-62`,
   `interceptor.cljc:234`, `std_interceptors.cljc:99-151`,
   `subs.cljc:66-280`. 10x's `tools/metamorphic.cljc:130-202`
   already encodes most of this as predicates."* **Highest leverage:**
   one upstream change, every downstream consumer gets a contract.

2. **(§3.4) Trace-tag the dispatch source.** *"Every consumer of the
   trace channel has the same problem: 'this epoch came from a
   user-fired dispatch, not a chained `:fx [:dispatch …]` effect' is
   not directly answerable. re-frame-pair tracks this via its own
   `claude-epoch-ids` set, walking before-id → after-id on every
   dispatch (~80 LOC at `runtime.cljs:853-941`). Adding a
   `:dispatch-id` tag (auto-generated counter, inherited by chained
   dispatches) to the event trace would let consumers filter
   directly. Implementation: thread a dynamic
   `*current-dispatch-tag*` through `re-frame.router/dispatch` /
   `dispatch-sync`, merge into `events.cljc:handle`'s trace.
   ~20 LOC."* Collapses the largest single chunk of re-frame-pair
   workaround code.

3. **(§3.2) Emit the subscription dependency graph.** *"Subscriptions
   know their input signals at runtime — `subs.cljc:185` already
   traces `:input-signals` as `reagent-id`s. Promoting that to
   `:input-query-vs` (the same walk, query-vector shape) would let
   consumers draw the dep graph directly. Today re-frame-pair
   reverse-engineers it by ordering `:sub/run` traces in execution
   order (`runtime.cljs:445-477`); a 10-line change in
   `cache-and-return` (push `:re-frame/query-v` onto the reaction's
   metadata) and 3 lines in `deref-input-signals` makes the graph
   first-class."* Powers the "why did X re-render" recipe without
   asking the user to click through 10x.

4. **(§3.5) Retain handler source via a side-table.** *"`(meta f)`
   on a registered event handler returns nil because re-frame's
   interceptor wrapper (`std_interceptors.cljc:73-154`) hides the
   user's fn behind a fresh closure. A side-table at
   `re-frame.core/handler-meta` populated at `reg-event-*` /
   `reg-sub` / `reg-fx` time, capturing `(meta &form)` plus
   `(meta handler-fn)` keyed by `[kind id]`, with a public
   `(handler-meta kind id)` accessor, would let any consumer
   (10x, re-frame-pair, custom devtools) report 'where is this
   handler defined?' reliably. ~30 LOC. Replaces re-frame-pair's
   pending `rfp-rsg` macro entirely; benefits 10x's Event panel
   too."*

5. **(§3.3) Expose a runtime version constant.** *"`re-frame.core/version`
   doesn't exist at runtime — re-frame-pair's `read-version-of`
   returns `:unknown` for re-frame and re-frame-10x; `version-floors`
   keeps placeholders nil. Add a `(def version "x.y.z")` populated
   by the existing `day8.dev/git-app-version!` macro at compile time,
   so re-frame-pair's `discover-app.sh` floor enforcement can
   actually run. ~5 LOC. Pair with the identical fix in
   re-frame-10x; re-com already does this."* Lowest leverage of the
   five, but quickest to ship.

## 6. See also

- `docs/handler-source-meta.md` — the structural-cause analysis for
  the §3.5 gap (path 1, 2, 3 trade-offs).
- `docs/inspirations-debux.md` — companion survey of re-frame-debux's
  shape, especially §6 (the improvement plan that shipped
  `wrap-handler!` / `unwrap-handler!`) and §7 (the top-N
  ranked-takeaways structure mirrored here).
- `STATUS.md` *Post-spike additions* (`rfp-hjj` Phase 1) and *Next
  actions / v0.2 deferred backlog* (`rfp-rsg` and the cross-rig
  pointer to the upstream `rfd-8g9` work that this document is the
  re-frame-side counterpart to).
