# Companion change for re-frame-10x

> Audience: a re-frame-10x maintainer. This document proposes one change to re-frame-10x that re-frame-pair (a Claude Code skill for introspecting re-frame apps) would benefit from. Read [`docs/initial-spec.md`](./initial-spec.md) — particularly Appendix A and §3.2 — for context. **No upstream patch is being proposed yet; this is a survey + design proposal so the change can be discussed and implemented if there's agreement.**

10x's epoch buffer is re-frame-pair's trace substrate (per spec §3.2), but the paths into it (the inlined re-frame version slug, the `[:epochs ...]` keys, the match record shape) are all internal. Promoting a documented `day8.re-frame-10x.public` namespace with stable read and mutation accessors would decouple re-frame-pair from version-probe hacks and internal-path knowledge, and it would let any other tool (error reporters, performance monitors, custom inspectors) build on the same epoch model without forking 10x.

---

## A2. Public namespace in re-frame-10x

**Spec proposal (verbatim from `docs/initial-spec.md` Appendix A):**

> 10x's epoch structures are re-frame-pair's trace substrate (§3.2) but are not a public API. Promote the needed fields (epoch id, event vector, interceptor chain, app-db before/after, sub-runs, renders, timings) to a documented namespace — e.g. `day8.re-frame-10x.public` — so re-frame-pair's adapter has a stable contract.

### Why this matters

- **For re-frame-pair.** Removes the coupling to `inlined-rf-version-paths` (the version-slug probe hardcoded as `["v1v3v0"]` plus runtime fallback enumeration) and to internal app-db paths (`[:epochs :match-ids]`, `[:epochs :matches-by-id]`, `[:epochs :selected-epoch-id]`). A stable contract shrinks `runtime.cljs` significantly and survives 10x version bumps without code changes here.
- **For re-frame-10x.** Separates the devtool UI from the epoch data substrate. Establishes 10x's per-dispatch grouping ("epoch") as a canonical concept that tools can build on, rather than a 10x-internal struct that consumers reverse-engineer. Other observability tools — error reporters that want app-db deltas, performance dashboards that want render timings, custom panels — gain the same foothold re-frame-pair currently hand-rolls.

### Current state in re-frame-10x

**Inlined re-frame.** 10x bundles its own copy of re-frame to keep its own devtool state out of the user's app-db. The bundled namespace tree is at `/home/mike/code/re-frame-10x/src/day8/re_frame_10x/inlined_deps/re_frame/v1v3v0/re_frame/...` and is imported into 10x as e.g. `day8.re-frame-10x.inlined-deps.re-frame.v1v3v0.re-frame.core` (see line 6 of `navigation/epochs/events.cljs`). The version slug `v1v3v0` is owned by 10x's build process. Bumping re-frame in 10x produces a new slug, which in turn moves the namespace path that any external introspector has to follow.

**Epoch buffer state owner.** `/home/mike/code/re-frame-10x/src/day8/re_frame_10x/navigation/epochs/events.cljs:99–108`. The `::receive-new-traces` event constructor writes the canonical paths:

```clojure
{:db (-> db
         (assoc-in [:traces :all] retained-traces)
         (update :epochs assoc
                 :matches            retained-matches
                 :match-ids          match-ids
                 :matches-by-id      (zipmap match-ids retained-matches)
                 :parse-state        parse-state
                 :sub-state          new-sub-state
                 :subscription-info  subscription-info)
         (cond-> select-latest? (assoc-in [:epochs :selected-epoch-id] (last match-ids))))}
```

These three keys (`:match-ids`, `:matches-by-id`, `:selected-epoch-id`) under `[:epochs]` are the exact paths re-frame-pair currently reads.

**Match record shape** (`navigation/epochs/events.cljs:78–81`):

```clojure
(map (fn [match sub-match t] {:match-info match
                              :sub-state  sub-match
                              :timing     t})
     new-matches subscription-matches timing)
```

`:match-info` is a vector of raw `re-frame.trace` events covering one dispatch. `:sub-state` is per-subscription instance state from `metam/subscription-match-state`. `:timing` is `{:re-frame/event-run-time, :re-frame/event-time, :re-frame/event-handler-time, :re-frame/event-dofx-time}`.

**Trace-event taxonomy in `:match-info`** (`/home/mike/code/re-frame-10x/src/day8/re_frame_10x/tools/metamorphic.cljc`):

- `:event` (line 176) — the re-frame event itself; carries tags `:event`, `:app-db-before`, `:app-db-after`, `:effects`, `:coeffects`, `:interceptors`.
- `:event/handler` (line 179) — the handler fn invocation.
- `:event/do-fx` (line 182) — the `:do-fx` interceptor's `:after` phase.
- `:sub/run` (line 140), `:sub/create` (line 136), `:sub/dispose` (line 143) — subscription lifecycle events.
- `:render` (line 161) — Reagent component render.
- `:raf` / `:raf-end` (lines 84, 87) — animation frame markers.
- `:re-frame.router/fsm-trigger` (line 43) — router state transitions.
- `:reagent/quiescent`, `:sync` (line 204) — end-of-match markers.

The trace vocabulary is owned by `re-frame.trace` (a re-frame namespace). 10x defines no new op-types; it consumes re-frame's.

**Navigation/mutation events** (`navigation/epochs/events.cljs`):

- `::previous` (line 114) — cursor moves to prior epoch.
- `::next` (line 122) — cursor moves to next epoch.
- `::most-recent` (line 132) — jump to head of buffer.
- `::load <id>` (line 140) — jump to specific id.
- `::replay` (line 147) — re-fire the selected event.
- `::reset-current-epoch-app-db` (line 178) — the time-travel mechanism: resets userland `app-db` to the selected epoch's pre-state. Each navigation event triggers this, but only when `:app-db-follows-events?` is true.

**Settings.** `:app-db-follows-events?` (default `true`) is registered at `/home/mike/code/re-frame-10x/src/day8/re_frame_10x/panels/settings/events.cljs:166`:

```clojure
(rf/reg-event-db
 ::app-db-follows-events?
 [(rf/path [:settings :app-db-follows-events?]) rf/trim-v
  (local-storage/save "app-db-follows-events?")]
 (fn [_ [follows-events?]]
   follows-events?))
```

Loaded from local-storage with `:or true` default elsewhere in `events.cljs`.

### v1 workaround in re-frame-pair

**Version-slug probe** (`scripts/re_frame_pair/runtime.cljs:235–252`):

```clojure
(def ^:private inlined-rf-known-version-paths
  "Best-known 10x-inlined re-frame version slugs. Tried first so the
   common case is one aget-path lookup. If none match, we fall back
   to enumerating whatever child keys live under
   `day8.re_frame_10x.inlined_deps.re_frame` at runtime — see
   `ten-x-inlined-rf`. Update this list opportunistically; the
   fallback handles fresh 10x releases on its own."
  ["v1v3v0"])
```

The fallback (lines ~257–268) enumerates keys under `day8.re_frame_10x.inlined_deps.re_frame` at runtime and probes each for the expected `re_frame.db.app_db` shape. This makes the skill survive a new slug without code change, but it is reaching across two abstraction layers (build-time inlining + 10x's choice of re-frame) that no consumer should know about.

**`read-10x-epochs`** (line 302):

```clojure
(defn read-10x-epochs
  "Raw matches from 10x's epoch buffer in chronological order.
   Throws ex-info with :reason :ten-x-missing if 10x is not loaded
   (or its inlined-deps version path isn't recognised).

   Each element is a 10x match record:
     {:match-info <vec-of-raw-traces> :sub-state <map> :timing <map>}
   Pass through `coerce-epoch` to translate to the §4.3a shape."
  []
  (let [a (ten-x-app-db-ratom)]
    (when-not a (throw (ex-info "re-frame-10x epoch buffer unreachable" ...)))
    (let [db    @a
          ids   (get-in db [:epochs :match-ids] [])
          by-id (get-in db [:epochs :matches-by-id] {})]
      (mapv #(get by-id %) ids))))
```

Direct deref of the inlined re-frame's `app-db` via three hardcoded paths.

**`coerce-epoch`** (line 510) translates a 10x match record into re-frame-pair's §4.3a epoch shape, producing keys: `:id`, `:t`, `:time-ms`, `:event`, `:coeffects`, `:effects`, `:effects/fired`, `:interceptor-chain`, `:app-db/diff`, `:subs/ran`, `:subs/cache-hit`, `:renders`. The §4.3a shape is the *de facto* contract a public 10x ns would formalise.

**Downstream consumers** (every function in runtime.cljs that calls `read-10x-epochs`):

- `epoch-count` (line 573) — needs ordered ids count.
- `last-epoch` (line 580) — needs head of buffer.
- `epoch-at-id` / `epoch-by-id` (line 585+) — needs id → match lookup.
- `epochs-since` (line 600) — needs ordered ids + lookup by id.
- `epochs-in-last-ms` (line 639) — reads all, filters by timestamp.
- `find-where` (line 665) — walks buffer in reverse, filters by predicate.
- `find-all-where` (line 676) — collects all matching epochs.
- `last-claude-epoch` (line 760) — finds most recent Claude-tagged epoch.
- `collect-after-dispatch` (line 833) — finds first new epoch after a given id.

Every one of these relies on `[:epochs :match-ids]` ordering, `[:epochs :matches-by-id]` lookup, and the `{:match-info :sub-state :timing}` match shape being stable.

### Proposed change

**Proposed namespace.** `day8.re-frame-10x.public` (alternatives: `.api`, `.epochs`, `.introspect` — the maintainer chooses; spec uses `.public` as a placeholder).

**Public read API.** Functions (not naked atom derefs), so the namespace can hide where the data actually lives:

```clojure
(ns day8.re-frame-10x.public
  "Stable API for accessing 10x's epoch buffer. Suitable for tools,
   error reporters, observability integrations.")

;; Buffer query
(defn epochs []
  "All epochs in the ring buffer, oldest first.
   Returns: vec of public epoch records.")

(defn epoch-ids []
  "Ordered vec of all epoch ids (oldest first).")

(defn epoch-at-id [id]
  "Lookup epoch by id, or nil if aged out.")

(defn selected-epoch-id []
  "Current cursor position. May be nil if buffer is empty.")

;; Per-epoch accessors (stable contract over the public record shape)
(defn epoch-event              [epoch] ...)   ;; the dispatched event vector
(defn epoch-interceptor-chain  [epoch] ...)   ;; vec of interceptor ids
(defn epoch-app-db-diff        [epoch] ...)   ;; clojure.data/diff shape over before/after
(defn epoch-effects            [epoch] ...)   ;; raw effects map
(defn epoch-coeffects          [epoch] ...)
(defn epoch-subs-ran           [epoch] ...)   ;; subs that recomputed
(defn epoch-subs-cache-hit     [epoch] ...)   ;; subs that hit the cache
(defn epoch-renders            [epoch] ...)   ;; component renders
(defn epoch-timings            [epoch] ...)   ;; per-phase timing breakdown
```

**Public epoch-record schema.** The hardest design question. Two options:

- **Option 1: mirror the internal map shape.** Cheap; couples consumers to `:match-info` / `:sub-state` / `:timing`.
- **Option 2: define a normalised shape** (close to §4.3a in re-frame-pair's spec, which `coerce-epoch` already produces). More work, but it lets 10x change its internal struct without breaking consumers.

**Recommendation: Option 2.** A normalised public record:

```clojure
{:id                  <numeric trace id>
 :event               [<kw> ...]      ;; the dispatched event vector
 :t                   <ms>            ;; timestamp (performance.now()-style)
 :time-ms             <num>           ;; total duration
 :coeffects           {...}
 :effects             {...}
 :effects/fired       [...]           ;; flattened: [{:fx-id :dispatch :value [...]} ...]
 :interceptor-chain   [<kw> ...]
 :app-db/diff         {:before ... :after ... :only-before ... :only-after ...}
 :subs/ran            [...]
 :subs/cache-hit      [...]
 :renders             [...]
 :timings             {...}}
```

The mapping from internal match → public record is small (~30 LOC) and lives in `day8.re-frame-10x.public`. Internal paths stay private.

**Public mutation API.** Two options for navigating / replaying epochs:

- **Option A: re-export the existing events under stable kws** — e.g. `[:day8.re-frame-10x.public/previous]`, `[:day8.re-frame-10x.public/load <id>]`, `[:day8.re-frame-10x.public/replay]`. Documented as "dispatch on the user's app's re-frame; 10x picks them up via its inlined re-frame's event listener" (which is how it works today for the existing internal kws).
- **Option B: wrapper fns** — `(day8.re-frame-10x.public/select-epoch id)`, `(... select-next)`, `(... replay-current)` — that internally dispatch the existing internal events.

**Recommendation: Option A.** It mirrors how 10x already works internally, doesn't add a new layer, and is more transparent to tools that already think in re-frame events.

**Versioning.** Mark the namespace `^:experimental` until at least one external consumer (re-frame-pair) ships against it; promote to `:stable` after that. This signals to other potential consumers that the contract is stabilising.

### Scope estimate

One new namespace `day8/re-frame-10x/public.cljs`, ~150–200 LOC: the public read fns, the internal-record-to-public-record mapper, the mutation event wrappers (Option B) or kw aliases (Option A). README addition documenting the new ns and its stability marker. No changes to existing 10x code — purely additive.

### Compatibility / migration notes

- **Strictly additive.** The new namespace is the only thing that changes. The internal epoch-assembly pipeline and the 10x UI continue to use the internal paths.
- **Hides the inlined-version slug.** The public ns must not leak `v1v3v0`. It accesses 10x's own data through 10x's own bindings, never through the slugged path. Once this lands, re-frame-pair can delete `inlined-rf-known-version-paths` entirely.
- **No coordination with A1 required.** A1 (re-frame's own `re-frame.introspect`) and A2 are independent. They land in any order; re-frame-pair adopts each as it appears.
- **Version-skew handling.** Tools should be able to detect whether a particular 10x build has the public ns. Recommendation: expose a small `(version)` or `(capabilities)` fn so consumers can branch.

### Open questions

1. **Namespace name.** `.public`, `.api`, `.epochs`, `.introspect`? `.public` is the spec's placeholder; any stable choice works.
2. **Mutation API shape.** Stable event kws (Option A) vs. wrapper fns (Option B). Re-frame-pair will adapt to either; the maintainer picks.
3. **Fields to expose.** `coerce-epoch` doesn't currently use everything in the match record. Worth surfacing, even as raw maps:
   - `:sub-state` carries per-subscription instance lifecycle. Tools that want to ask "was sub X created in this epoch?" or "how many sub instances ran?" need it.
   - `:timing` has three phases (`:re-frame/event-run-time`, `:re-frame/event-handler-time`, `:re-frame/event-dofx-time`). re-frame-pair currently only surfaces the outer figure; exposing all three lets tools profile handler vs. effect time.
   Recommendation: include them as `:sub-state-raw` and `:timings` in the public record so future tools aren't blocked.
4. **Lifecycle hooks.** Should the public API include `on-epoch-complete` / `on-epoch-start`? Or is that better delivered by re-frame's A4 (`register-epoch-cb`) in core? Recommendation: defer to A4; if A4 lands, 10x can simply forward.
5. **Settings exposure.** Should `(day8.re-frame-10x.public/app-db-follows-events?)` be part of the public API, given that navigation events behave differently when it's false? Probably yes — at minimum the public mutation API should document the dependency.
