(ns re-frame-pair.runtime.ten-x-adapter
  (:require [clojure.data :as data]
            [re-frame-pair.runtime.re-com :as re-com]))

;; 10x runs its own *inlined* copy of re-frame to keep its devtool state
;; out of the user's app-db. The inlined ns is munged
;; `day8.re-frame-10x.inlined-deps.re-frame.<inlined-version>.re-frame.*` —
;; the version slug (`v1v3v0` today) bumps when 10x updates its bundled
;; re-frame. We probe a list of known slugs so a future bump only needs
;; one new candidate added here.
;;
;; The epoch buffer lives in that inlined re-frame's app-db at:
;;   [:epochs :match-ids]         — ordered ids vec (chronological)
;;   [:epochs :matches-by-id]     — id -> match record
;;   [:epochs :selected-epoch-id] — current cursor (for time-travel)
;; Each match: {:match-info <vec-of-traces> :sub-state <map> :timing <map>}.
;;
;; `:match-info` is a vector of raw `re-frame.trace` events: one with
;; `:op-type :event` carrying tags {:event :app-db-before :app-db-after
;; :effects :coeffects :interceptors}, plus child traces for sub
;; runs (`:sub/run`), sub creates (`:sub/create`), renders (`:render`),
;; etc. The match-id is the `:id` of the first trace in match-info.
;; (See day8.re-frame-10x.tools.metamorphic for the parser that builds
;; these matches.)

(def ^:private inlined-rf-known-version-paths
  "Best-known 10x-inlined re-frame version slugs. Used by the
   `ten-x-inlined-rf` legacy path as a fallback when 10x's
   `day8.re-frame-10x.public` namespace isn't loaded — see
   `ten-x-public` for the preferred path. If none match, we fall
   back further to enumerating whatever child keys live under
   `day8.re_frame_10x.inlined_deps.re_frame` at runtime.

   rf1-jum landed `day8.re-frame-10x.public` upstream as the stable
   surface. Once consumers can rely on every supported 10x release
   carrying it, this vec (and `ten-x-inlined-rf`'s walk) can be
   deleted entirely. Until then it's the bridge for older 10x JARs."
  ["v1v3v0"])

(defn aget-path
  "Walk a JS object via a vector of property names. Returns nil if any
   step throws or yields nil. Public so sibling submodules
   (native-epoch, dispatch, health) can reuse without :refer'ing a
   `defn-`."
  [obj path]
  (reduce (fn [acc k]
            (when acc
              (try (aget acc k) (catch :default _ nil))))
          obj path))

(defn- ^js js-keys-array
  "Object.keys via interop. Returns a JS array; callers convert to seq.
   Wrapped in try/catch because aget'd JS values may be primitives."
  [o]
  (try
    (when o (js/Object.keys o))
    (catch :default _ nil)))

(defn ten-x-public
  "The JS object for `day8.re-frame-10x.public` if loaded, else nil.
   This is rf1-jum's stable surface — preferred over inlined-rf
   walking because it's intentionally version-pinned (no `v1v3v0`
   slug to chase) and forward-compatible (capabilities advertised
   via `(public/version)` and `(public/capabilities)`).

   Detection: presence of `loaded_QMARK_` is the contract. Older
   10x JARs (pre-rf1-jum) lack the public ns; consumers fall back
   to `ten-x-inlined-rf` for those builds. Public so the epochs
   bridge (`latest-epoch-id`, `epoch-count`) can short-circuit to
   the public surface without re-deriving the probe."
  []
  (when-let [g (some-> js/goog .-global)]
    (let [pub (aget-path g ["day8" "re_frame_10x" "public"])]
      (when (and pub (aget pub "loaded_QMARK_"))
        pub))))

(defonce ^:private inlined-rf-fallback-warned? (atom false))

(defn- warn-on-inlined-rf-fallback!
  "One-time `console.warn` when 10x is loaded but its public ns
   (rf1-jum) isn't — i.e. re-frame-pair has had to take the legacy
   inlined-rf walk. Idempotent (compare-and-set! gate) so a busy
   epoch read loop doesn't spam. Telemetry hook: lets us observe
   whether the pre-rf1-jum fallback is hit in real consumer builds
   before we commit to deleting it (rfp-xjdr)."
  []
  (when (compare-and-set! inlined-rf-fallback-warned? false true)
    (try
      (js/console.warn
        (str "[re-frame-pair] re-frame-10x is loaded but its public "
             "namespace `day8.re-frame-10x.public` is missing — "
             "using the legacy inlined-rf fallback. Upgrade "
             "re-frame-10x to rf1-jum or newer to silence this; "
             "the fallback may be removed in a future re-frame-pair "
             "release."))
      (catch :default _ nil))))

(defn- ten-x-inlined-rf
  "Legacy path: the JS object for 10x's inlined `re-frame` package.
   Used when `ten-x-public` returns nil (older 10x JARs that don't
   ship rf1-jum's public ns).

   Strategy: try the known-version slugs first (cheap, deterministic),
   then fall back to enumerating every child key under
   `day8.re_frame_10x.inlined_deps.re_frame` and picking the first
   that has a `re_frame.db.app_db` underneath. The enum fallback
   means a fresh 10x release with a new slug works without any
   code change — `read-10x-epochs` no longer throws `:ten-x-missing`
   just because we shipped before the slug got added to the known
   list. Once every supported 10x carries `public`, both this fn
   and `inlined-rf-known-version-paths` can be deleted.

   Emits a one-time `console.warn` (`warn-on-inlined-rf-fallback!`)
   the first time we resolve via this path on a runtime where
   `ten-x-public` is nil — telemetry to observe whether the
   pre-rf1-jum fallback is still load-bearing in practice."
  []
  (when-let [g (some-> js/goog .-global)]
    (let [base      (aget-path g ["day8" "re_frame_10x" "inlined_deps" "re_frame"])
          via-known (some (fn [ver] (aget-path base [ver "re_frame"]))
                          inlined-rf-known-version-paths)
          result    (or via-known
                        ;; Fallback: enumerate any child key and look for one
                        ;; that carries the expected `re_frame.db.app_db` shape.
                        (when base
                          (some (fn [k]
                                  (let [candidate (aget-path base [k "re_frame"])]
                                    (when (aget-path candidate ["db" "app_db"])
                                      candidate)))
                                (array-seq (or (js-keys-array base) #js [])))))]
      (when (and result
                 (not @inlined-rf-fallback-warned?)
                 (nil? (ten-x-public)))
        (warn-on-inlined-rf-fallback!))
      result)))

(defn ten-x-app-db-ratom
  "The Reagent ratom holding 10x's internal app-db. Nil if 10x missing.
   Public so sibling submodules (epochs, time-travel) can reach it
   without :refer'ing a `defn-`."
  []
  (some-> (ten-x-inlined-rf) (aget-path ["db" "app_db"])))

(defn read-10x-all-traces
  "Full trace stream from 10x. This is `re-frame.trace`'s raw stream,
   with every op-type intact: `:event :sub/run :sub/create
   :sub/dispose :render :raf :raf-end :event/handler` etc. The
   skeleton stored at `(:match-info match)` only retains the subset
   that fits 10x's epoch start/end markers (event-run, fsm-trigger,
   end-of-match, sync) — for renders and sub-runs we have to come
   back here. Returns [] when 10x isn't loaded so callers can no-op
   gracefully.

   rf1-jum: prefers `day8.re-frame-10x.public/all-traces` when
   loaded; falls back to direct app-db ratom read for older 10x."
  []
  (if-let [pub (ten-x-public)]
    (or ((aget pub "all_traces")) [])
    (or (some-> (ten-x-app-db-ratom) deref :traces :all)
        [])))

(defn ten-x-rf-core
  "10x's inlined re-frame.core JS namespace object. Holds .dispatch,
   .dispatch_sync, .subscribe — used by the undo adapter to drive
   10x's own event bus."
  []
  (some-> (ten-x-inlined-rf) (aget-path ["core"])))

(defn ten-x-loaded?
  "True if re-frame-10x has loaded into this runtime AND we can reach
   its inlined re-frame app-db. The latter is the actual gating
   condition — without it, every `read-10x-epochs` call would fail."
  []
  (boolean (ten-x-app-db-ratom)))

(defn- normalize-10x-match
  "Return `match` with the legacy 10x keys that `coerce-epoch` consumes.
   `day8.re-frame-10x.public` intentionally exposes renamed fields
   (`:sub-state-raw`, `:timings`) so its public shape can evolve without
   promising internal names. The runtime keeps one coercion path by
   normalising those aliases back to the match shape used below."
  [match]
  (when match
    (cond-> match
      (contains? match :sub-state-raw)
      (assoc :sub-state (:sub-state-raw match))

      (contains? match :timings)
      (assoc :timing (:timings match)))))

(defn read-10x-epochs
  "Matches from 10x's epoch buffer in chronological order. Throws
   ex-info with `:reason :ten-x-missing` if 10x isn't loaded.

   Each element carries (at minimum) `{:match-info <vec-of-raw-traces>}`.
   Pass through `coerce-epoch` to translate to the §4.3a shape.

   rf1-jum: when `day8.re-frame-10x.public` is loaded, returns its
   public-epoch shape `{:id :match-info :sub-state-raw :timings}` —
   the renamed sub-state / timings keys are the public contract.
   When it isn't (older 10x), falls back to the legacy inlined-rf
   path which returns 10x's internal shape
   `{:match-info :sub-state :timing}`. Public-shape aliases are
   normalised before return so downstream coercion uses the same
   intermediate regardless of which 10x surface supplied it."
  []
  (if-let [pub (ten-x-public)]
    (mapv normalize-10x-match ((aget pub "epochs")))
    (let [a (ten-x-app-db-ratom)]
      (when-not a
        (throw (ex-info "re-frame-10x epoch buffer unreachable"
                        {:reason :ten-x-missing
                         :tried-known-paths inlined-rf-known-version-paths
                         :hint (str "10x is not loaded, or its inlined "
                                    "re-frame namespace doesn't carry the "
                                    "expected `re_frame.db.app_db` ratom. "
                                    "ten-x-inlined-rf already tries every "
                                    "child key under inlined_deps.re_frame "
                                    "as a fallback — if you're hitting this, "
                                    "10x itself probably isn't preloaded. "
                                    "Note: as of rf1-jum, 10x ships a "
                                    "`day8.re-frame-10x.public` ns that "
                                    "is preferred over this path.")})))
      (let [db    @a
            ids   (get-in db [:epochs :match-ids] [])
            by-id (get-in db [:epochs :matches-by-id] {})]
        (mapv #(normalize-10x-match (get by-id %)) ids)))))

(defn match-id
  "10x's id for a match — the id of the first trace in :match-info.
   Public so epoch / dispatch bridges can use it."
  [match]
  (or (:id match)
      (-> match :match-info first :id)))

(defn read-10x-match-by-id
  "Single match from 10x's epoch buffer by id. Throws ex-info with
   `:reason :ten-x-missing` if 10x isn't loaded.

   Uses the public `epoch-by-id` surface when present; otherwise reads
   the legacy `[:epochs :matches-by-id]` map directly. This avoids
   rebuilding the full chronological match vector for callers that
   already know the epoch id."
  [id]
  (if-let [pub (ten-x-public)]
    (normalize-10x-match ((aget pub "epoch_by_id") id))
    (let [a (ten-x-app-db-ratom)]
      (when-not a
        (throw (ex-info "re-frame-10x epoch buffer unreachable"
                        {:reason :ten-x-missing
                         :tried-known-paths inlined-rf-known-version-paths
                         :hint (str "10x is not loaded, or its inlined "
                                    "re-frame namespace doesn't carry the "
                                    "expected `re_frame.db.app_db` ratom. "
                                    "ten-x-inlined-rf already tries every "
                                    "child key under inlined_deps.re_frame "
                                    "as a fallback — if you're hitting this, "
                                    "10x itself probably isn't preloaded. "
                                    "Note: as of rf1-jum, 10x ships a "
                                    "`day8.re-frame-10x.public` ns that "
                                    "is preferred over this path.")})))
      (some-> (get-in @a [:epochs :matches-by-id id])
              normalize-10x-match))))

(defn find-trace
  "First :match-info entry whose :op-type matches. Public — used by
   the dispatch + epoch submodules."
  [match op-type]
  (some #(when (= op-type (:op-type %)) %) (:match-info match)))

(defn- traces-of [match op-type]
  (filterv #(= op-type (:op-type %)) (:match-info match)))

(defn- diff-app-db
  "clojure.data/diff of the event trace's :app-db-before / :app-db-after.
   Returns the §4.3a :app-db/diff shape."
  [event-trace]
  (let [tags (:tags event-trace)
        b    (:app-db-before tags)
        a    (:app-db-after  tags)
        [only-before only-after _both] (data/diff b a)]
    {:before      b
     :after       a
     :only-before only-before
     :only-after  only-after}))

(defn- flatten-fx
  "Translate an :effects map into the §4.3a :effects/fired vector. Each
   non-`:fx` key contributes one entry; `:fx` (a vector of [id val]
   tuples — see re-frame.fx) is flattened into individual entries so
   the caller sees one entry per fx-handler invocation."
  [effects]
  (when (map? effects)
    (->> effects
         (mapcat (fn [[k v]]
                   (cond
                     (and (= :fx k) (sequential? v))
                     (->> v
                          (remove nil?)
                          (map (fn [[id val]] {:fx-id id :value val})))

                     :else
                     [{:fx-id k :value v}])))
         vec)))

(defn- match-trace-ids
  "[first-id last-id] of the match's :match-info, or nil if empty."
  [match]
  (let [mi (:match-info match)]
    (when (seq mi)
      [(:id (first mi)) (:id (last mi))])))

(defn- traces-in-id-range
  "Slice of `all-traces` whose `:id` falls within [first-id last-id]
   inclusive. The full trace stream lives at `[:traces :all]` in 10x's
   internal app-db; matches store only their boundary traces in
   :match-info, so for sub-runs and renders we have to come back to
   the stream and filter by id range (matching 10x's own
   `::filtered-by-epoch-always` sub)."
  [all-traces first-id last-id]
  (filterv (fn [t] (let [id (:id t)]
                     (and id (<= first-id id last-id))))
           all-traces))

(defn- demunge-component-name
  "Reagent's `:component-name` tag arrives munged: dots-and-underscores
   like `re_com.box.h_box`. `cljs.core/demunge` returns the dotted
   hyphenated form (`re-com.box.h-box`) — close enough for our
   `re-com?` prefix check and category heuristics, and stays edn-clean."
  [s]
  (when (string? s) (cljs.core/demunge s)))

(defn- sub-input-deps
  "Map of query-v → `{:input-query-vs ... :input-query-sources ...}`,
   sourced from `:sub/run` trace tags in the live trace stream.
   Upstream rf-3p7 item 3 (re-frame commit fa90f70) added
   `:input-query-vs` alongside `:input-signals` on every `:sub/run`;
   rf-cna's subscribe macro additionally attaches
   `:re-frame/source {:file :line}` to each input query-v's meta when
   the user wrote `(rf.macros/subscribe ...)` inside the parent sub
   handler. We surface those source maps as a sibling
   `:input-query-sources` vec parallel to `:input-query-vs`.

   When a query-v re-runs multiple times (typical), the
   most-recent run wins — input deps don't change between runs
   for the same sub. Empty when re-frame predates fa90f70 or
   all-traces is empty (no enrichment then; consumers see
   `:input-query-vs` nil per entry). `:input-query-sources` is a
   vec the same length as `:input-query-vs` with `nil` slots for
   inputs subscribed via the bare `re-frame.core/subscribe` fn."
  [all-traces]
  (->> all-traces
       (filter #(= :sub/run (:op-type %)))
       (reduce (fn [m t]
                 (let [tags (:tags t)]
                   (if-let [q (:query-v tags)]
                     (let [inputs (:input-query-vs tags)]
                       (assoc m q
                              {:input-query-vs      inputs
                               :input-query-sources (when inputs
                                                      (mapv #(some-> % meta :re-frame/source)
                                                            inputs))}))
                     m)))
               {})))

(defn- sub-runs-from-state
  "§4.3a :subs/ran. 10x's `:match-info` doesn't carry `:sub/run` traces
   directly (`metam/parse-traces` strips them when building partitions);
   instead the post-epoch reaction state at
   `(-> match :sub-state :reaction-state)` records `:run? true` and
   `:order [:sub/run ...]` for each reaction that re-ran during the
   epoch. We pick those whose value *changed* — `:sub/traits
   :unchanged?` not set — and expose the user-facing query-v plus
   the dep-graph edges from the matching :sub/run trace's
   `:input-query-vs` tag (upstream rf-3p7 item 3 — re-frame commit
   fa90f70).

   `:time-ms` is not stored per-sub at this layer; omit it rather than
   make it up. Total sub time is in :timing :animation-frame-subs if
   needed."
  [match all-traces]
  ;; Scope the join inputs to traces that fall within this match's id
  ;; range. sub-input-deps does a key-by-query-v reduce, so feeding it
  ;; the unfiltered stream lets a query-v re-run in a LATER epoch
  ;; overwrite the meta that this epoch's :input-query-sources should
  ;; surface — the native rf-ybv path filters by id-range; the legacy
  ;; path has to do the same to stay in sync.
  (let [[first-id last-id] (match-trace-ids match)
        in-range           (if (and first-id last-id)
                             (traces-in-id-range all-traces first-id last-id)
                             all-traces)
        deps               (sub-input-deps in-range)]
    (->> (-> match :sub-state :reaction-state)
         (filter (fn [[_ sub]]
                   (and (some #{:sub/run} (:order sub))
                        (not (get-in sub [:sub/traits :unchanged?])))))
         (mapv (fn [[_ sub]]
                 (let [q    (:subscription sub)
                       info (get deps q)]
                   {:query-v             q
                    ;; Outer subscribe call site (rf-cna). Meta on the
                    ;; query-v stored in 10x's reaction-state — survives
                    ;; if 10x preserved meta when capturing the
                    ;; subscription (subs that hit cache reflect the
                    ;; first caller's source). Nil when subscribed via
                    ;; the bare re-frame.core/subscribe fn.
                    :subscribe/source    (some-> q meta :re-frame/source)
                    :input-query-vs      (:input-query-vs info)
                    ;; Per-input subscribe call sites (rf-cna). Vec
                    ;; parallel to :input-query-vs; nil entries when
                    ;; that input was subscribed via the bare fn.
                    :input-query-sources (:input-query-sources info)}))))))

(defn- sub-cache-hit-entry [q reason]
  {:query-v          q
   :subscribe/source (some-> q meta :re-frame/source)
   :cache-hit/reason reason})

(defn- unchanged-sub-runs-from-state
  [match]
  (->> (-> match :sub-state :reaction-state)
       (filter (fn [[_ sub]]
                 (and (some #{:sub/run} (:order sub))
                      (get-in sub [:sub/traits :unchanged?]))))
       (mapv (fn [[_ sub]]
               (sub-cache-hit-entry (:subscription sub)
                                    :sub/run-unchanged)))))

(defn- cached-sub-creates-from-traces
  [match all-traces]
  (when-let [[first-id last-id] (match-trace-ids match)]
    (->> (traces-in-id-range all-traces first-id last-id)
         (filter #(= :sub/create (:op-type %)))
         (filter #(true? (-> % :tags :cached?)))
         (keep #(-> % :tags :query-v))
         distinct
         (mapv #(sub-cache-hit-entry % :sub/create-cached)))))

(defn- sub-cache-hits-from-state
  "§4.3a :subs/cache-hit. Two signals land here:

   * `:sub/run` reactions whose final value was `=` to their prior value
     (`:sub/traits {:unchanged? true}`), so downstream subscriptions and
     views short-circuited.
   * `:sub/create` traces tagged `:cached? true`, where subscribe reused
     an existing reaction and did not re-run the computation.

   `:cache-hit/reason` keeps those cases distinguishable for consumers
   that need the sharper diagnostic."
  [match all-traces]
  (vec (concat (unchanged-sub-runs-from-state match)
               (cached-sub-creates-from-traces match all-traces))))

(defn- renders-from-traces
  "§4.3a :renders. Reagent records each component render as a
   `:op-type :render` trace with `:tags :component-name` (munged form,
   e.g. `re_com.box.h_box`). These are in the full trace stream, not
   the skeleton :match-info. Filter by the match's id range, demunge,
   and annotate for re-com.

   When re-com (rc-aeh) ships, every component routing through
   `re-com.debug/->attr` emits a sibling `:re-com/render` trace
   carrying `{:src {:file :line}}` on its tags. Reagent's `:render`
   trace tags only carry `:component-name`, so without this sibling
   the render entry has no source attribution. We pre-pass the slice
   to build a per-component-name :src map, then attach `:src` to the
   matching render entries. Pre-rc-aeh re-com (and any non-re-com
   render) carries no `:re-com/render` sibling — :src stays nil.

   Single pass over the :render traces: build the render entry and
   run `classify-render-entry` in one mapv so we don't allocate an
   intermediate vec just to hand it to the next mapv."
  [match all-traces]
  (when-let [[first-id last-id] (match-trace-ids match)]
    (let [slice         (traces-in-id-range all-traces first-id last-id)
          src-by-component
          ;; Component-name → :src from :re-com/render entries in the
          ;; slice. Multiple renders of the same component in one epoch
          ;; collapse to the latest :src — fine for the recipe since
          ;; the same component instance has a single source site.
          (->> slice
               (filter #(= :re-com/render (:op-type %)))
               (reduce (fn [m t]
                         (let [tags (:tags t)
                               comp (demunge-component-name
                                      (or (:component-name tags)
                                          (str (:operation t))))
                               src  (:src tags)]
                           (cond-> m src (assoc comp src))))
                       {}))]
      (->> slice
           (filter #(= :render (:op-type %)))
           (mapv (fn [t]
                   (let [tags  (:tags t)
                         comp  (demunge-component-name
                                 (or (:component-name tags) (str (:operation t))))
                         src   (get src-by-component comp)
                         entry {:component comp
                                :time-ms   (:duration t)
                                :reaction  (:reaction tags)}]
                     (re-com/classify-render-entry
                       (cond-> entry src (assoc :src src))))))))))

(defn- has-render-burst?
  "True if the match's :match-info contains a `:reagent/quiescent`
   close — meaning Reagent rendered before the match closed. These
   matches carry `:render` traces in their id range AND have
   :run? entries in their `:sub-state :reaction-state`.

   Two cases this catches:
   1. A queued user dispatch where the page was actively rendering:
      one match holds event + handler + renders + quiescent.
   2. A render-burst follow-up to a `dispatch-sync` (which closes its
      match at `:sync` *before* reagent renders): the next match has
      `:event nil` (or whatever the next event was) and ends at
      `:reagent/quiescent` once renders finish.

   When this match itself doesn't have a quiescent close (e.g. a
   `dispatch-sync` ending at :sync, or a match closed by the start of
   the next event), `coerce-epoch` looks at the immediately-following
   match for the render data."
  [match]
  (boolean
   (and match
        (some #(= :reagent/quiescent (:op-type %)) (:match-info match)))))

(defn- match-after
  "The match that comes immediately after `match` in chronological
   order in `all-matches`, or nil if `match` is the head."
  [match all-matches]
  (let [target-id (match-id match)
        tail      (drop-while #(not= target-id (match-id %)) all-matches)]
    (second tail)))

(defn- resolve-render-source
  "Pick the match that holds render data for `match`, working around
   re-frame + 10x's split between user-event and render epochs.

   Three cases:

   1. `match` itself ends at `:reagent/quiescent` — typical for a
      queued `dispatch` on an actively-rendering page. Render data
      is in this match's id range and sub-state. Return `match`.

   2. `match` ends at `:sync` (a `dispatch-sync`, e.g. our --trace
      bash path). Reagent renders fire on a later animation frame,
      so 10x splits them into the *next* match (nil `:event` tag,
      `:reagent/quiescent` close). Return that next match.

   3. Neither — page tab inactive / throttled, or the match is the
      head of the buffer with no follow-up yet. Return nil; the
      caller leaves `:renders` / `:subs/ran` / `:subs/cache-hit`
      blank rather than misleadingly empty.

   `all-matches` is 10x's full buffer in chronological order so we can
   reach for the next match. Tests pass it explicitly; live calls
   pull it from the runtime."
  [match all-matches]
  (or (when (has-render-burst? match) match)
      (let [nxt (match-after match all-matches)]
        (when (has-render-burst? nxt) nxt))))

(defn coerce-epoch
  "Translate a raw 10x match into the §4.3a epoch record. Returns nil
   when raw is nil. Public so callers that already pulled matches from
   10x's buffer can reshape them.

   `:app-db/diff`, `:event`, `:effects`, `:coeffects` always come from
   the user-event match — those are the things the user actually
   dispatched. `:subs/ran`, `:subs/cache-hit`, `:renders` come from
   `resolve-render-source` (which may step to the next match).

   Two arities:
     (coerce-epoch raw)
       Fetches `all-traces` and `all-matches` from 10x's app-db.
     (coerce-epoch raw {:keys [all-traces all-matches]})
       Pass explicit context (for batching across many epochs, or
       for tests with a synthetic environment).

   Note: `:renders[].src` is not populated — re-com's `:src` is not
   threaded into the render trace today. The `dom/*` ops do that join
   via the live DOM (§4.3b).

   `:debux/code` surfaces re-frame-debux's per-form trace payload when
   the user has wrapped a handler / sub / fx with
   `day8.re-frame.tracing/fn-traced` (or `defn-traced`). debux's
   `send-trace!` writes through `re-frame.trace/merge-trace!` into the
   `:code` tag of `*current-trace*` (re-frame-debux/src/day8/re_frame/
   debux/common/util.cljc:132). re-frame's `db-handler->interceptor`
   wraps user handlers in `(trace/with-trace {:op-type :event/handler})`,
   so `*current-trace*` at the moment debux emits is the inner
   `:event/handler` trace — not the outer `:event` trace. Read it from
   the `:event/handler`-typed entry in `:match-info`; absent → `nil`.
   See docs/inspirations-debux.md §3b for the bridge rationale and
   §3.0 for the on-demand-wrap recipe."
  ([raw]
   (coerce-epoch raw {:all-traces  (read-10x-all-traces)
                      :all-matches (when (ten-x-app-db-ratom)
                                     (read-10x-epochs))}))
  ([raw {:keys [all-traces all-matches]}]
   (when raw
     (let [raw           (normalize-10x-match raw)
           all-matches   (some->> all-matches (mapv normalize-10x-match))
           event-trace   (find-trace raw :event)
           tags          (:tags event-trace)
           handler-trace (find-trace raw :event/handler)
           render-src    (resolve-render-source raw all-matches)]
       {:id                (match-id raw)
        :t                 (:start event-trace)
        :time-ms           (:duration event-trace)
        :event             (:event tags)
        ;; Pinned at re-frame.events/handle entry, before any
        ;; interceptor (trim-v / unwrap / path / inject-cofx, …)
        ;; rewrote :event for the handler. Always present on
        ;; re-frame core's :event traces post-2026 release; older
        ;; re-frame degrades to the same value as :event since no
        ;; rewrite was tracked.
        :event/original    (get tags :event/original (:event tags))
        :coeffects         (:coeffects tags)
        :effects           (:effects tags)
        :effects/fired     (flatten-fx (:effects tags))
        ;; Just the chain of :id keywords — the raw interceptor records
        ;; carry :before / :after function refs that print as #object[...]
        ;; and don't survive the edn round-trip back through cljs-eval.
        ;; Callers who want the real interceptor map can hit
        ;; `registrar/describe :event <id>`.
        :interceptor-chain (mapv :id (:interceptors tags))
        :app-db/diff       (diff-app-db event-trace)
        :subs/ran          (when render-src (sub-runs-from-state render-src all-traces))
        :subs/cache-hit    (when render-src (sub-cache-hits-from-state render-src all-traces))
        :renders           (when render-src (renders-from-traces render-src all-traces))
        ;; Per-form trace from re-frame-debux's fn-traced — nil when
        ;; debux isn't on the classpath OR the handler wasn't wrapped.
        ;; Read from the inner :event/handler trace, not the outer
        ;; :event trace: debux's merge-trace! lands on *current-trace*,
        ;; which std_interceptors/db-handler->interceptor binds to the
        ;; :event/handler with-trace boundary at handler-call time.
        :debux/code        (-> handler-trace :tags :code)
        ;; Dispatch-site source (file/line) lifted from the event
        ;; vector's meta. Populated when the event was dispatched via
        ;; re-frame.macros/dispatch[-sync] (rf-hsl); nil for events
        ;; dispatched via the bare re-frame.core/dispatch fn or on a
        ;; re-frame predating those macros. Flattened from meta to a
        ;; top-level key so it survives the pr-str / cljs-eval boundary
        ;; back to bash (meta strips by default on edn output).
        :event/source       (some-> (:event tags) meta :re-frame/source)
        ;; Auto-generated dispatch correlation from re-frame core.
        ;; nil for events
        ;; dispatched on a re-frame predating that commit. The
        ;; :dispatch-id is unique per `re-frame.events/handle` entry;
        ;; :parent-dispatch-id is set when the event was queued from
        ;; within another handler's `:fx [:dispatch ...]`. Powers the
        ;; "is this MY dispatch or a chained child?" filter that used
        ;; to require ~80 LOC of before-id/after-id walking.
        :dispatch-id        (:dispatch-id tags)
        :parent-dispatch-id (:parent-dispatch-id tags)}))))
