;;;; re-frame-pair.runtime — injected helper namespace
;;;;
;;;; This file is evaluated by `scripts/inject-runtime.sh` on first
;;;; connect. It creates the `re-frame-pair.runtime` namespace inside
;;;; the running browser app and populates it with helpers that the
;;;; skill's ops call through `eval-cljs.sh`.
;;;;
;;;; Design invariants (see docs/initial-spec.md):
;;;;   - When re-frame core ships `register-epoch-cb` (rf-ybv,
;;;;     commit 4a53afb), runtime installs its own epoch-cb +
;;;;     trace-cb to consume assembled epochs and the trace stream
;;;;     directly. Falls back to reading 10x's epoch buffer when
;;;;     re-frame predates rf-ybv. (rfp-zl8 retired the old "no
;;;;     second register-trace-cb" rule — that was load-bearing on
;;;;     10x being the trace substrate, which native epoch-cb
;;;;     supersedes.)
;;;;   - The `session-id` sentinel below is re-read on every op. If
;;;;     it's gone, a full page refresh happened and the shim
;;;;     re-injects this file.
;;;;   - 10x internals accessed here are not a public API; several
;;;;     names marked with `TODO verify` need grounding in the spike.
;;;;
;;;; This file is source-of-truth for injection. The shell shim reads
;;;; it and ships the forms over nREPL — so keep it self-contained.

(ns re-frame-pair.runtime
  (:require [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            [re-frame.trace]
            [clojure.data :as data]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Session sentinel
;; ---------------------------------------------------------------------------
;;
;; A random UUID set once per injection. Every subsequent op reads it
;; through `eval-cljs.sh`. If a full page refresh has wiped the
;; browser-side runtime, reading the var throws and the shim knows to
;; re-inject.

(def session-id
  (str (random-uuid)))

(defn sentinel
  "Return the session sentinel. Used by the shim to confirm the runtime
   is still alive in the current browser runtime."
  []
  {:ok?        true
   :session-id session-id
   :installed  (js/Date.now)})

;; ---------------------------------------------------------------------------
;; app-db read/write
;; ---------------------------------------------------------------------------

(defn snapshot
  "Full current app-db."
  []
  @db/app-db)

(defn app-db-at
  "Read a path in app-db."
  [path]
  (get-in @db/app-db path))

(defn app-db-reset!
  "Replace app-db with v. Logged explicitly via `tap>` so the human
   sees what the agent changed."
  [v]
  (tap> {:re-frame-pair/op :app-db/reset
         :previous          @db/app-db
         :next              v
         :t                 (js/Date.now)})
  (reset! db/app-db v)
  {:ok? true})

(defn schema
  "Opt-in app-db schema. Apps that want one can write a spec/malli
   schema to `:re-frame-pair/schema` in app-db (typically via an
   `after` interceptor). Returns nil if the app hasn't opted in."
  []
  (get @db/app-db :re-frame-pair/schema))

;; ---------------------------------------------------------------------------
;; Registrar introspection
;; ---------------------------------------------------------------------------

;; The registrar is `re-frame.registrar/kind->id->handler`, an atom
;; of `{kind {id handler}}`. Verified against current re-frame source.

(defn registrar-list
  "Enumerate registered ids under a kind (:event / :sub / :fx / :cofx)."
  [kind]
  (-> (get-in @registrar/kind->id->handler [kind])
      keys
      sort
      vec))

(defn- interceptor-chain-ids
  "Walk an interceptor chain and pull out the ordered :id keys."
  [chain]
  (mapv :id chain))

(defn registrar-describe
  "Inspect re-frame's registrar.

   Three arities:

   `(registrar-describe)` — return every registered id grouped by kind:
       {:ok? true :by-kind {:event [...] :sub [...] :fx [...] :cofx [...]}}
     Useful as a one-shot survey. Equivalent to
     `(into {} (for [k kinds] [k (registrar-list k)]))`.

   `(registrar-describe kind)` — list ids under one kind. Same shape
     as `registrar-list` but wrapped in {:ok? true :kind ... :ids ...}
     for a uniform return shape.

   `(registrar-describe kind id)` — full handler metadata.
     - For :event — returns kind (reg-event-db / -fx / -ctx, inferred
       from the terminal interceptor's :id) and :interceptor-ids.
     - For :sub / :fx / :cofx — the handler is a plain function; no
       interceptor chain.
     - Source form is not retained by the registrar today, so :source
       is always :not-retained until re-frame A7 lands."
  ([]
   {:ok? true
    :by-kind (into {}
                   (for [[kind id->handler] @registrar/kind->id->handler]
                     [kind (-> id->handler keys sort vec)]))})
  ([kind]
   {:ok? true
    :kind kind
    :ids (registrar-list kind)})
  ([kind id]
   (let [entry (get-in @registrar/kind->id->handler [kind id])]
     (cond
       (nil? entry)
       {:ok? false :reason :not-registered :kind kind :id id}

       (= kind :event)
       (let [terminal-id (-> entry last :id)]
         {:ok?             true
          :kind            (case terminal-id
                             :db-handler  :reg-event-db
                             :fx-handler  :reg-event-fx
                             :ctx-handler :reg-event-ctx
                             :unknown)
          :interceptor-ids (interceptor-chain-ids entry)
          :source          :not-retained})

       :else
       {:ok? true :kind kind :source :not-retained}))))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

;; `re-frame.subs/query->reaction` is the subscription cache atom.
;; Its keys are *not* plain query-vecs — they are `[cache-key-map dyn-vec]`
;; pairs, where `cache-key-map` is
;;   {:re-frame/query-v <query-v>
;;    :re-frame/q       <query-id>
;;    :re-frame/lifecycle :reactive}
;; (See `re-frame.subs/cache-key`.) Extract `:re-frame/query-v` from
;; the first element of each cache-key to recover the query vector.

(defn extract-query-vs
  "Pull `:re-frame/query-v` out of each cache key. `cache-keys` is a
   sequence of `[cache-key-map dyn-vec]` pairs produced by
   `re-frame.subs/cache-key`. Public so tests can exercise the
   extraction without a live re-frame."
  [cache-keys]
  (->> cache-keys
       (keep (fn [k] (get-in k [0 :re-frame/query-v])))
       (sort-by str)
       vec))

(defn subs-live
  "Query vectors currently held in re-frame's subscription cache."
  []
  (extract-query-vs (some-> subs/query->reaction deref keys)))

(defn subs-sample
  "Subscribe to query-v and deref once. See docs/initial-spec.md §4.1
   on caching/lifecycle — fine for one-shot probes, not for repeated
   polling outside a reactive context."
  [query-v]
  (try
    @(rf/subscribe query-v)
    (catch :default e
      {:ok? false :reason :sub-error :message (.-message e)})))

(defn handler-source
  "Source location of a registered handler, read from the metadata
   that ClojureScript's source-map machinery attaches to compiled
   fns. For `:event` handlers, drills into the terminal interceptor's
   `:before` (which is the fn the user wrote in
   `reg-event-{db,fx,ctx}`); other kinds use the stored value
   directly.

   Returns:
     {:ok? true :kind ... :id ... :file ... :line ... :column ...}
   or
     {:ok? false :reason :not-registered :kind ... :id ...}
     {:ok? false :reason :no-source-meta :kind ... :id ...}

   `:no-source-meta` is common: not every CLJS compile mode populates
   fn metadata. shadow-cljs dev builds with source-maps usually do;
   advanced-compiled production builds typically don't. When the meta
   is missing we surface the fact cleanly rather than guessing — tell
   the user to grep for the handler id if you need the location.

   This is the v1 hack; A7 in Appendix A proposes that re-frame
   retain source forms in dev so a richer `(handler-source)` can also
   return the form itself."
  [kind id]
  (let [stored (registrar/get-handler kind id)
        ;; For :event the registered value is an interceptor chain;
        ;; the user fn is the terminal interceptor's :before.
        f      (cond
                 (nil? stored)   nil
                 (= kind :event) (some-> stored last :before)
                 :else           stored)
        m      (when f (meta f))]
    (cond
      (nil? stored)
      {:ok? false :reason :not-registered :kind kind :id id}

      (and m (or (:file m) (:line m)))
      {:ok?    true
       :kind   kind
       :id     id
       :file   (:file m)
       :line   (:line m)
       :column (:column m)}

      :else
      {:ok? false :reason :no-source-meta :kind kind :id id})))

;; ---------------------------------------------------------------------------
;; re-frame-10x epoch buffer adapter
;; ---------------------------------------------------------------------------
;;
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

;; Forward-declared — defined in the re-com awareness section below.
;; Used by `renders-for` to annotate render entries with re-com hints.
(declare classify-render-entry)

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

(defn- aget-path
  "Walk a JS object via a vector of property names. Returns nil if any
   step throws or yields nil."
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

(defn- ten-x-public
  "The JS object for `day8.re-frame-10x.public` if loaded, else nil.
   This is rf1-jum's stable surface — preferred over inlined-rf
   walking because it's intentionally version-pinned (no `v1v3v0`
   slug to chase) and forward-compatible (capabilities advertised
   via `(public/version)` and `(public/capabilities)`).

   Detection: presence of `loaded_QMARK_` is the contract. Older
   10x JARs (pre-rf1-jum) lack the public ns; consumers fall back
   to `ten-x-inlined-rf` for those builds."
  []
  (when-let [g (some-> js/goog .-global)]
    (let [pub (aget-path g ["day8" "re_frame_10x" "public"])]
      (when (and pub (aget pub "loaded_QMARK_"))
        pub))))

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
   and `inlined-rf-known-version-paths` can be deleted."
  []
  (when-let [g (some-> js/goog .-global)]
    (let [base (aget-path g ["day8" "re_frame_10x" "inlined_deps" "re_frame"])
          via-known (some (fn [ver] (aget-path base [ver "re_frame"]))
                          inlined-rf-known-version-paths)]
      (or via-known
          ;; Fallback: enumerate any child key and look for one that
          ;; carries the expected `re_frame.db.app_db` shape.
          (when base
            (some (fn [k]
                    (let [candidate (aget-path base [k "re_frame"])]
                      (when (aget-path candidate ["db" "app_db"])
                        candidate)))
                  (array-seq (or (js-keys-array base) #js []))))))))

(defn- ten-x-app-db-ratom
  "The Reagent ratom holding 10x's internal app-db. Nil if 10x missing."
  []
  (some-> (ten-x-inlined-rf) (aget-path ["db" "app_db"])))

(defn- read-10x-all-traces
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

(defn- ten-x-rf-core
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
   `{:match-info :sub-state :timing}`. Both shapes preserve
   `:match-info`, which is what `coerce-epoch` and `match-id`
   walk — so callers don't have to branch on which 10x they're
   talking to."
  []
  (if-let [pub (ten-x-public)]
    ((aget pub "epochs"))
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
        (mapv #(get by-id %) ids)))))

(defn- match-id
  "10x's id for a match — the id of the first trace in :match-info."
  [match]
  (-> match :match-info first :id))

(defn- find-trace [match op-type]
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
  "Map of query-v → :input-query-vs, sourced from `:sub/run` trace
   tags in the live trace stream. rfp-fxv / rf-3p7 item 3
   (re-frame commit fa90f70) added `:input-query-vs` alongside
   `:input-signals` on every `:sub/run`; this map is the lookup
   table that turns the raw trace stream into a per-sub dep
   graph for `subs/ran`'s output.

   When a query-v re-runs multiple times (typical), the
   most-recent run wins — input deps don't change between runs
   for the same sub. Empty when re-frame predates fa90f70 or
   all-traces is empty (no enrichment then; consumers see
   `:input-query-vs` nil per entry)."
  [all-traces]
  (->> all-traces
       (filter #(= :sub/run (:op-type %)))
       (reduce (fn [m t]
                 (let [tags (:tags t)]
                   (if-let [q (:query-v tags)]
                     (assoc m q (:input-query-vs tags))
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
   `:input-query-vs` tag (rfp-fxv / rf-3p7 item 3 — re-frame commit
   fa90f70).

   `:time-ms` is not stored per-sub at this layer; omit it rather than
   make it up. Total sub time is in :timing :animation-frame-subs if
   needed."
  [match all-traces]
  (let [deps (sub-input-deps all-traces)]
    (->> (-> match :sub-state :reaction-state)
         (filter (fn [[_ sub]]
                   (and (some #{:sub/run} (:order sub))
                        (not (get-in sub [:sub/traits :unchanged?])))))
         (mapv (fn [[_ sub]]
                 (let [q (:subscription sub)]
                   {:query-v        q
                    :input-query-vs (get deps q)}))))))

(defn- sub-cache-hits-from-state
  "§4.3a :subs/cache-hit. Re-frame doesn't trace genuine cache hits
   (deref-without-recompute), but we surface the closest signal 10x
   exposes: subs that re-ran AND produced an equal value
   (`:sub/traits {:unchanged? true}`). These are the subs whose
   downstream short-circuited because the result was `=` to the prior
   value — semantically what `:subs/cache-hit` is documenting in §4.3a
   (sub did its work but didn't propagate change)."
  [match]
  (->> (-> match :sub-state :reaction-state)
       (filter (fn [[_ sub]]
                 (and (some #{:sub/run} (:order sub))
                      (get-in sub [:sub/traits :unchanged?]))))
       (mapv (fn [[_ sub]] {:query-v (:subscription sub)}))))

(defn- renders-from-traces
  "§4.3a :renders. Reagent records each component render as a
   `:op-type :render` trace with `:tags :component-name` (munged form,
   e.g. `re_com.box.h_box`). These are in the full trace stream, not
   the skeleton :match-info. Filter by the match's id range, demunge,
   and run through `classify-render-entry` for re-com annotation."
  [match all-traces]
  (when-let [[first-id last-id] (match-trace-ids match)]
    (->> (traces-in-id-range all-traces first-id last-id)
         (filter #(= :render (:op-type %)))
         (mapv (fn [t]
                 (let [tags (:tags t)
                       comp (demunge-component-name
                              (or (:component-name tags) (str (:operation t))))]
                   {:component comp
                    :time-ms   (:duration t)
                    :reaction  (:reaction tags)})))
         (mapv classify-render-entry))))

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
   `:code` tag of the current trace event (re-frame-debux/src/day8/
   re_frame/debux/common/util.cljc:132); we just expose that here under
   a debux-namespaced key so consumers can spot it without colliding
   with our own keys. Absent → `nil`. See docs/inspirations-debux.md
   §3b for the bridge rationale and §3.0 for the on-demand-wrap recipe."
  ([raw]
   (coerce-epoch raw {:all-traces  (read-10x-all-traces)
                      :all-matches (when (ten-x-app-db-ratom)
                                     (read-10x-epochs))}))
  ([raw {:keys [all-traces all-matches]}]
   (when raw
     (let [event-trace (find-trace raw :event)
           tags        (:tags event-trace)
           render-src  (resolve-render-source raw all-matches)]
       {:id                (match-id raw)
        :t                 (:start event-trace)
        :time-ms           (:duration event-trace)
        :event             (:event tags)
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
        :subs/cache-hit    (when render-src (sub-cache-hits-from-state render-src))
        :renders           (when render-src (renders-from-traces render-src all-traces))
        ;; Per-form trace from re-frame-debux's fn-traced — nil when
        ;; debux isn't on the classpath OR the handler wasn't wrapped.
        :debux/code        (:code tags)
        ;; rfp-fxv / rf-3p7 item 2 — auto-generated dispatch correlation
        ;; from re-frame core (commit af024c3). nil for events
        ;; dispatched on a re-frame predating that commit. The
        ;; :dispatch-id is unique per `re-frame.events/handle` entry;
        ;; :parent-dispatch-id is set when the event was queued from
        ;; within another handler's `:fx [:dispatch ...]`. Powers the
        ;; "is this MY dispatch or a chained child?" filter that used
        ;; to require ~80 LOC of before-id/after-id walking.
        :dispatch-id        (:dispatch-id tags)
        :parent-dispatch-id (:parent-dispatch-id tags)}))))

;; ---------------------------------------------------------------------------
;; Native epoch path (rfp-zl8 / re-frame rf-ybv)
;; ---------------------------------------------------------------------------
;;
;; re-frame core's `register-epoch-cb` (commit 4a53afb) ships assembled
;; epoch records once per `:event` trace. We drain into a session-local
;; ring buffer and a sibling trace ring buffer (the latter feeds renders
;; / sub-runs correlation by id range — they aren't attached to the
;; native epoch by `:child-of` because they fire outside the synchronous
;; handler frame).
;;
;; Feature detection is via JS interop on `re_frame.trace.register_epoch_cb`
;; — the symbol doesn't exist when re-frame predates rf-ybv, so a direct
;; `(re-frame.trace/register-epoch-cb ...)` would fail compile against
;; the runtime-test build (which pins re-frame 1.4.5 from clojars).
;; `register-trace-cb` has been part of re-frame for years, so the trace
;; subscription uses the direct namespace reference.

(defonce native-epoch-buffer
  (atom {:entries [] :max-size 50}))

(defonce native-trace-buffer
  (atom {:entries [] :max-size 5000}))

(defonce ^:private native-epoch-cb-installed? (atom false))
(defonce ^:private native-trace-cb-installed? (atom false))

(defn- receive-native-epochs!
  "register-epoch-cb callback. Appends each delivered epoch to the
   ring buffer, keeping the most recent :max-size entries."
  [epochs]
  (swap! native-epoch-buffer
         (fn [{:keys [entries max-size]}]
           {:entries  (vec (take-last max-size (into entries epochs)))
            :max-size max-size})))

(defn- receive-native-traces!
  "register-trace-cb callback. Appends raw traces from each delivery
   batch to the trace ring buffer."
  [batch]
  (swap! native-trace-buffer
         (fn [{:keys [entries max-size]}]
           {:entries  (vec (take-last max-size (into entries batch)))
            :max-size max-size})))

(defn- register-epoch-cb-fn
  "JS-interop accessor for `re-frame.trace/register-epoch-cb`. Returns
   nil when re-frame predates rf-ybv (commit 4a53afb) — the JS symbol
   simply won't exist on that build.

   We look up via `goog.global` rather than referencing the var
   directly so this file still compiles against re-frame 1.4.5 (the
   runtime-test build's pinned dep), which doesn't ship the var."
  []
  (when-let [g (some-> js/goog .-global)]
    (aget-path g ["re_frame" "trace" "register_epoch_cb"])))

(defn install-native-epoch-cb!
  "Register a `::re-frame-pair` epoch callback if re-frame core ships
   `register-epoch-cb`. Idempotent. Silent no-op on older re-frame —
   legacy callers fall back through `read-10x-epochs`."
  []
  (when-not @native-epoch-cb-installed?
    (when-let [register-fn (register-epoch-cb-fn)]
      (reset! native-epoch-cb-installed? true)
      (register-fn ::re-frame-pair receive-native-epochs!))))

(defn install-native-trace-cb!
  "Register a `::re-frame-pair-traces` trace callback to feed the
   trace ring buffer. Idempotent. Costs one closure invocation per
   debounce tick, regardless of trace volume."
  []
  (when-not @native-trace-cb-installed?
    (reset! native-trace-cb-installed? true)
    (re-frame.trace/register-trace-cb ::re-frame-pair-traces
                                      receive-native-traces!)))

(defn native-epochs
  "Read-only accessor for the native-epoch-buffer entries
   (chronological order, oldest first)."
  []
  (:entries @native-epoch-buffer))

(defn native-traces
  "Read-only accessor for the native-trace-buffer entries
   (chronological order, oldest first)."
  []
  (:entries @native-trace-buffer))

(defn- find-native-epoch-by-id
  "Walk the native-epoch-buffer for the entry with matching :id.
   Returns nil when not present (older re-frame, aged out, or the
   epoch was never delivered through the cb because tracing was
   disabled when it fired)."
  [id]
  (some #(when (= id (:id %)) %) (native-epochs)))

(defn- next-native-epoch-id
  "The :id of the epoch immediately after `epoch` in `all-epochs`,
   or nil if `epoch` is the latest. `all-epochs` is the buffer in
   chronological order."
  [epoch all-epochs]
  (let [target-id (:id epoch)]
    (->> all-epochs
         (drop-while #(<= (:id %) target-id))
         first
         :id)))

(defn- traces-in-native-epoch-range
  "Trace stream slice belonging to `epoch`. Range is
   [epoch's :id, next-epoch's :id) when a successor exists,
   otherwise unbounded above. This bounds renders / sub-runs that
   `assemble-epochs` couldn't attach via `:child-of` (because they
   fire on a later RAF tick or from a render-time deref outside the
   event's with-trace boundary)."
  [epoch all-epochs traces]
  (let [first-id (:id epoch)
        next-id  (next-native-epoch-id epoch all-epochs)]
    (filterv (fn [t] (let [id (:id t)]
                       (and id (<= first-id id)
                            (or (nil? next-id) (< id next-id)))))
             traces)))

(defn- subs-ran-from-native-traces
  "Walk a trace-stream slice for `:sub/run` entries; emit one
   `{:query-v ... :input-query-vs ...}` per unique query-v
   (latest run wins). Native analogue of `sub-runs-from-state`,
   driven off the trace stream because most `:sub/run` traces fire
   from render-time derefs (with `:child-of nil`) and so don't make
   it onto the native epoch's `:sub-runs` vec — that vec only
   carries direct `:child-of` children of the `:event` trace."
  [traces]
  (->> traces
       (filter #(= :sub/run (:op-type %)))
       (reduce (fn [m t]
                 (let [tags (:tags t)]
                   (if-let [q (:query-v tags)]
                     (assoc m q (:input-query-vs tags))
                     m)))
               {})
       (mapv (fn [[q input-qvs]]
               {:query-v q :input-query-vs input-qvs}))))

(defn- subs-cache-hit-from-native-traces
  "Walk a trace-stream slice for `:sub/create` entries with
   `:cached? true` tag; emit one `{:query-v ...}` per unique
   query-v. Native analogue of `sub-cache-hits-from-state` — but
   driven off re-frame core's own `:cached?` signal (set in
   `re-frame.subs/subscribe` when `cache-lookup` finds an existing
   reaction). Matches §4.3a's 'subs dereffed but cached' definition
   more precisely than 10x's `:unchanged?` heuristic did."
  [traces]
  (->> traces
       (filter #(= :sub/create (:op-type %)))
       (filter #(true? (-> % :tags :cached?)))
       (keep #(-> % :tags :query-v))
       distinct
       (mapv (fn [q] {:query-v q}))))

(defn- renders-from-native-traces
  "Walk a trace-stream slice for `:render` entries; emit one render
   entry per `:render` trace, classified for re-com awareness via
   `classify-render-entry`. Native analogue of `renders-from-traces`."
  [traces]
  (->> traces
       (filter #(= :render (:op-type %)))
       (mapv (fn [t]
               (let [tags (:tags t)
                     comp (demunge-component-name
                           (or (:component-name tags) (str (:operation t))))]
                 {:component comp
                  :time-ms   (:duration t)
                  :reaction  (:reaction tags)})))
       (mapv classify-render-entry)))

(defn coerce-native-epoch
  "Translate a `register-epoch-cb`-delivered native epoch into the
   §4.3a shape — the same record shape that `coerce-epoch` produces
   from a 10x match. Pure; takes the trace stream + sibling epochs
   explicitly so tests can pass synthetic context.

   The native epoch (output of `re-frame.trace/assemble-epochs`)
   carries `{:id :event :dispatch-id :parent-dispatch-id
   :app-db/before :app-db/after :coeffects :effects :interceptors
   :sub-runs :sub-creates :event-handler :event-do-fx :start :end
   :duration}`.

   `:subs/ran`, `:subs/cache-hit`, and `:renders` are derived from
   the trace-stream slice in this epoch's id range, NOT from the
   native epoch's `:sub-runs` / `:sub-creates` vecs — those only
   carry direct `:child-of` children of the `:event` trace, which
   misses render-time sub recomputes and (per `assemble-epochs`'s
   own docstring) all renders.

   `:debux/code` is read from the `:event-handler` trace's `:tags`
   (debux's `merge-trace!` lands on `*current-trace*`, which is
   the `:event/handler` with-trace boundary at the moment a
   `fn-traced` user handler emits its per-form trace).

   Two arities:
     (coerce-native-epoch raw)
       Pulls trace stream + sibling epochs from the live ring buffers.
     (coerce-native-epoch raw {:keys [traces all-epochs]})
       Explicit context for tests / batched coercion."
  ([raw]
   (coerce-native-epoch raw {:traces     (native-traces)
                             :all-epochs (native-epochs)}))
  ([raw {:keys [traces all-epochs]}]
   (when raw
     (let [in-range (traces-in-native-epoch-range raw all-epochs traces)
           [only-before only-after _both] (data/diff (:app-db/before raw)
                                                    (:app-db/after raw))]
       {:id                 (:id raw)
        :t                  (:start raw)
        :time-ms            (:duration raw)
        :event              (:event raw)
        :coeffects          (:coeffects raw)
        :effects            (:effects raw)
        :effects/fired      (flatten-fx (:effects raw))
        :interceptor-chain  (mapv :id (:interceptors raw))
        :app-db/diff        {:before      (:app-db/before raw)
                             :after       (:app-db/after raw)
                             :only-before only-before
                             :only-after  only-after}
        :subs/ran           (subs-ran-from-native-traces in-range)
        :subs/cache-hit     (subs-cache-hit-from-native-traces in-range)
        :renders            (renders-from-native-traces in-range)
        :debux/code         (-> raw :event-handler :tags :code)
        :dispatch-id        (:dispatch-id raw)
        :parent-dispatch-id (:parent-dispatch-id raw)}))))

(defn debux-runtime-api?
  "True iff `day8.re-frame.tracing.runtime/runtime-api?` is loaded
   in this runtime — i.e. the on-demand instrumentation Phase 2
   API (wrap-handler! / unwrap-handler! / etc.) is available.
   False both when day8.re-frame/tracing isn't on the classpath at
   all AND when an older release is loaded that ships only the
   fn-traced macro (pre-rfd-8g9; runtime ns landed in re-frame-debux
   commit 4ed07c9, runtime-api? probe-var landed in commit 6b04e6b
   under ci-hpg / rf-yvu).

   Used by SKILL.md's 'Trace a handler / sub / fx form-by-form'
   recipe to dispatch between the wrap-handler!/unwrap-handler!
   path (preferred — clean unwrap, no source-eval round-trip) and
   the manual fn-traced AST-rewrite fallback.

   Probes the JS-side munged path
   `day8.re_frame.tracing.runtime.runtime_api_QMARK_` rather than
   CLJS `resolve` because that pattern doesn't require the
   namespace to be on the cljs source path at runtime.cljs compile
   time — the helper still works in builds that don't bundle debux.
   Switched from probing `wrap_handler_BANG_` to the dedicated
   `runtime_api_QMARK_` var (rf-yvu Phase 2): the upstream now owns
   the detection contract, so the symbol won't be renamed away in
   a refactor of the wrap/unwrap surface itself."
  []
  (boolean
    (when-let [g (some-> js/goog .-global)]
      (aget-path g ["day8" "re_frame" "tracing" "runtime" "runtime_api_QMARK_"]))))

(defn latest-epoch-id
  "Id of 10x's newest match, or nil if the buffer is empty / 10x is
   not loaded.

   Cheap path: 10x keeps an ordered `:match-ids` vec at
   `[:epochs :match-ids]` in its app-db; the head of it IS what we
   want. Avoids `read-10x-epochs`'s full per-match map-rebuild —
   significant for `watch-epochs.sh`, which polls this at ~100ms cadence
   and used to construct a fresh 25-entry coerced-match vec every tick.

   rf1-jum: prefers `day8.re-frame-10x.public/latest-epoch-id` when
   loaded (also a single :match-ids head read; just removes the
   inlined-rf-version-path coupling). Falls back for older 10x."
  []
  (if-let [pub (ten-x-public)]
    ((aget pub "latest_epoch_id"))
    (when-let [a (ten-x-app-db-ratom)]
      (last (get-in @a [:epochs :match-ids])))))

(defn epoch-count
  "Total matches in 10x's ring buffer.

   rf1-jum: prefers the public surface's `epoch-count` (cheap —
   reads `:match-ids` length). Older 10x falls back to
   `(count (read-10x-epochs))` which goes through the legacy path."
  []
  (if-let [pub (ten-x-public)]
    ((aget pub "epoch_count"))
    (count (read-10x-epochs))))

(defn epoch-by-id
  "Return the coerced epoch with matching id, or nil. Prefers the
   native-epoch-buffer (rfp-zl8 / rf-ybv); falls back to
   `read-10x-epochs` when re-frame predates rf-ybv or the epoch has
   aged out of the native buffer."
  [id]
  (or (when-let [raw (find-native-epoch-by-id id)]
        (coerce-native-epoch raw))
      (when (or (ten-x-loaded?)
                (not @native-epoch-cb-installed?))
        (->> (read-10x-epochs)
             (some #(when (= id (match-id %)) %))
             coerce-epoch))))

(defn last-epoch
  "Most recently appended epoch, coerced. Prefers the native-epoch-
   buffer; falls back to 10x. Nil if neither has any epochs."
  []
  (or (some-> (native-epochs) last coerce-native-epoch)
      (when (or (ten-x-loaded?)
                (not @native-epoch-cb-installed?))
        (some-> (read-10x-epochs) last coerce-epoch))))

(defn epochs-since
  "Epochs appended *after* the given id. Returns a map:

     {:epochs       [...]       ;; coerced epochs
      :id-aged-out? true|false} ;; did the requested id still exist?

   Semantics:
     - `id` nil                -> all epochs in buffer, :id-aged-out? false
     - `id` matches head       -> [], :id-aged-out? false
     - `id` matches some epoch -> epochs strictly after it, :id-aged-out? false
     - `id` not found in buffer (e.g. aged out of the ring) -> [],
                                  :id-aged-out? true

   Returns a map (not a vector with metadata) because edn-via-nREPL
   discards metadata on the round-trip; callers at the CLI need the
   aged-out signal in the value itself."
  [id]
  (let [epochs (read-10x-epochs)]
    (cond
      (nil? id)
      {:epochs (mapv coerce-epoch epochs)
       :id-aged-out? false}

      (some #(= id (match-id %)) epochs)
      {:epochs (mapv coerce-epoch (rest (drop-while #(not= id (match-id %)) epochs)))
       :id-aged-out? false}

      :else
      {:epochs []
       :id-aged-out? true
       :requested-id id})))

(defn- now-ms
  "Same clock re-frame.trace uses for trace `:start`: `performance.now()`
   when available (page-load-relative monotonic ms), else `Date.now()`
   (epoch ms). Match the trace clock — comparing across the two gives
   nonsense (perf.now is in the thousands, Date.now in the trillions)."
  []
  (if (and (exists? js/performance) (exists? js/performance.now))
    (.now js/performance)
    (.now js/Date)))

(defn epochs-in-last-ms
  "Epochs appended in the last N ms (pull). Compares against the event
   trace's `:start` timestamp.

   `:start` comes from re-frame.trace via `interop/now` — that's
   `performance.now()` (page-load-relative) when available, not wall-clock
   `Date.now()`. The cutoff has to be on the same clock or every epoch
   looks ancient."
  [ms]
  (let [cutoff (- (now-ms) ms)]
    (->> (read-10x-epochs)
         (filter (fn [m] (let [t (:start (find-trace m :event))]
                           (and t (>= t cutoff)))))
         (mapv coerce-epoch))))

(defn find-where
  "Walk 10x's epoch buffer in reverse chronological order and return
   the first epoch matching the predicate (a 1-arg fn taking a coerced
   epoch map), or nil if no match.

   Primary forensic op — 'find the epoch where X happened'. Examples:

     ;; find the epoch where :auth-state flipped to :expired
     (find-where
       (fn [e] (= :expired (get-in (:only-after (:app-db/diff e))
                                   [:auth-state]))))

     ;; find the epoch that fired a 500-status xhrio
     (find-where
       (fn [e] (some (fn [fx] (and (= :http-xhrio (:fx-id fx))
                                    (= 500 (get-in (:value fx) [:status]))))
                     (:effects/fired e))))

   Most recent match wins — usually what you want for 'how did I get
   into this state?' post-mortems."
  [pred]
  (->> (read-10x-epochs)
       (map coerce-epoch)
       reverse
       (filter pred)
       first))

(defn find-all-where
  "Like find-where but returns every matching epoch, newest first. Use
   when you want the full trajectory of a path — 'every epoch where
   :cart changed' — not just the most recent transition."
  [pred]
  (->> (read-10x-epochs)
       (map coerce-epoch)
       reverse
       (filterv pred)))

;; ---------------------------------------------------------------------------
;; Console capture
;; ---------------------------------------------------------------------------
;;
;; A ring buffer of `js/console.{log,warn,error,info,debug}` calls,
;; tagged with a `:who` marker so the agent can ask "what did MY
;; dispatch log, vs the user's app?". Installed by
;; `install-console-capture!` from `health` so it's idempotent and
;; runs once per browser runtime. Defaults to `:app` (i.e. user code);
;; `tagged-dispatch!` / `tagged-dispatch-sync!` flip it to `:claude`
;; for the duration of their dispatch.
;;
;; Async note: `tagged-dispatch!`'s handler runs out of band, so
;; console.* calls *inside the handler* still tag `:app` — only
;; synchronous calls during the enqueue itself catch `:claude`. Use
;; `tagged-dispatch-sync!` (which runs the handler synchronously)
;; when you need handler output tagged.

;; `console-log` and `current-who` are exposed (no ^:private) so the
;; runtime-test build can reset / inspect them without warnings. The
;; runtime API consumers should still go through `console-tail-since`
;; / `tagged-dispatch-{!,sync!}` rather than poking the atoms.

(defonce console-log
  (atom {:entries [] :next-id 0 :max-size 500}))

(defonce current-who (atom :app))

(defn- stringify-arg
  "Stringify a console-call argument for the buffer. Avoids holding
   live JS objects (DOM nodes, ratoms, large data structures) that
   would inflate memory and prevent GC."
  [v]
  (cond
    (nil? v)     "nil"
    (string? v)  v
    (number? v)  (str v)
    (boolean? v) (str v)
    (keyword? v) (str v)
    :else        (try (str v)
                      (catch :default _ "<unstringifiable>"))))

(defn- append-console-entry!
  "Append a single console event to the ring buffer. `who` defaults
   to `@current-who`; pass `:handler-error` explicitly for the
   exception-catch path."
  ([level args stack] (append-console-entry! level args stack @current-who))
  ([level args stack who]
   (swap! console-log
          (fn [{:keys [entries next-id max-size]}]
            {:entries  (vec (take-last max-size
                                       (conj entries
                                             {:id    next-id
                                              :ts    (js/Date.now)
                                              :level level
                                              :args  (mapv stringify-arg args)
                                              :who   who
                                              :stack stack})))
             :next-id  (inc next-id)
             :max-size max-size}))))

(defn install-console-capture!
  "Wrap `js/console.{log,warn,error,info,debug}` so each call appends
   to the console-log ring buffer in addition to the original
   behaviour. Idempotent — guarded by a window marker so a re-inject
   doesn't double-wrap.

   Silent no-op when there is no browser-side `js/window`
   (e.g. shadow-cljs's `:node-test` build); the runtime still loads
   so unit tests can exercise unrelated machinery."
  []
  (when (and (exists? js/window)
             (not (aget js/window "__rfp_console_capture__")))
    (aset js/window "__rfp_console_capture__" true)
    (doseq [level [:log :warn :error :info :debug]]
      (let [n    (name level)
            orig (aget js/console n)]
        (aset js/window (str "__rfp_orig_console_" n) orig)
        (aset js/console n
              (fn [& args]
                (let [stack (when (#{:error :warn} level)
                              (try (.-stack (js/Error.))
                                   (catch :default _ "")))]
                  (append-console-entry! level args stack))
                (.apply orig js/console (apply array args))))))))

(defn console-tail-since
  "Return console entries with `:id >= since-id`, optionally filtered
   by `:who` (one of `:app` / `:claude` / `:handler-error`, or nil =
   all). Returns `{:ok? true :entries [...] :next-id <int>}`.

   `:next-id` is the id the next captured entry will receive — pass
   it back as `since-id` on the next call to tail incrementally."
  ([since-id]      (console-tail-since since-id nil))
  ([since-id who]
   (let [{:keys [entries next-id max-size]} @console-log
         filtered (cond->> entries
                    (some? since-id) (filter #(>= (:id %) since-id))
                    (some? who)      (filter #(= (:who %) who)))]
     {:ok?      true
      :entries  (vec filtered)
      :next-id  next-id
      :max-size max-size})))

;; ---------------------------------------------------------------------------
;; Claude-dispatch tagging — rfp-fxv collapses the pre-rf-3p7
;; before-id/after-id correlation onto upstream's auto-generated
;; :dispatch-id. The session-local set still exists so
;; last-claude-epoch can answer "the most recent epoch I dispatched"
;; without the caller threading the id back to us, but it now stores
;; dispatch-ids (UUIDs) rather than 10x match-ids.
;; ---------------------------------------------------------------------------

(defonce claude-dispatch-ids
  (atom #{}))

(defn- recent-dispatch-id
  "After a `dispatch-sync`, read `re-frame.trace/traces` for the
   most recent `:event` trace and return its `:dispatch-id`.
   `re-frame.trace/traces` is updated synchronously inside
   `re-frame.events/handle`'s `with-trace` finish-trace (the cb
   delivery to 10x runs through a ~50ms debounce, but the source
   atom updates immediately), so this resolves the id we just
   generated as long as no cb fired between finish-trace and our
   read — acceptable race in practice (single-threaded JS, the cb
   is goog.functions/debounce'd 50ms out from the LAST trace).

   nil when re-frame predates rf-3p7 commit af024c3 (no
   :dispatch-id tag generated)."
  []
  (->> @re-frame.trace/traces
       reverse
       (some (fn [t] (when (= :event (:op-type t))
                       (-> t :tags :dispatch-id))))))

(defn tagged-dispatch!
  "Dispatch an event (queued) — the handler runs out of band, so the
   :dispatch-id is generated only when handle eventually fires.

   `current-who` is set to `:claude` for the duration of the enqueue
   call; the handler itself runs out of band, so handler-side console
   output tags `:app`. Use `tagged-dispatch-sync!` when you need
   handler output tagged.

   Returns {:ok? true :queued? true :event ...}. `:dispatch-id` and
   `:epoch-id` are nil — dispatch is queued, the epoch appears once
   the handler runs."
  [event-v]
  (reset! current-who :claude)
  (try
    (rf/dispatch event-v)
    (finally
      (reset! current-who :app)))
  {:ok? true
   :queued? true
   :event event-v
   :epoch-id nil
   :dispatch-id nil})

(defn tagged-dispatch-sync!
  "`dispatch-sync` the event and read back the auto-generated
   `:dispatch-id` from re-frame's trace stream so callers can
   correlate the eventual epoch to this dispatch.

   The :dispatch-id comes from re-frame core (rf-3p7 commit af024c3)
   — generated at every `re-frame.events/handle` entry, emitted on
   the `:event` trace's `:tags`. We capture it from
   `@re-frame.trace/traces` immediately after dispatch-sync returns
   (the trace is finished synchronously inside `handle`'s
   `with-trace`).

   The async note on epoch resolution still applies: trace events
   for this dispatch are emitted synchronously, but 10x's
   `::receive-new-traces` event (which appends to
   `:epochs :matches-by-id`) doesn't fire until re-frame.trace's
   ~50ms cb-delivery debounce flushes. Use `dispatch-and-collect`
   for the full async round-trip, or call `collect-after-dispatch`
   with the returned `:dispatch-id` after a bash-side wait.

   Handler errors: re-frame's default error handler logs to console
   and re-throws, which would propagate through `cljs-eval` as an
   nREPL `:err` and break the bb shim's edn parsing. We catch and
   return a structured `:reason :handler-threw` instead.

   Returns:
     {:ok? true :event ev :dispatch-id <uuid|nil> :epoch-id nil}
     {:ok? false :reason :handler-threw :error ... :error-data ...}"
  [event-v]
  (reset! current-who :claude)
  (try
    (try
      (rf/dispatch-sync event-v)
      (let [dispatch-id (recent-dispatch-id)]
        (when dispatch-id
          (swap! claude-dispatch-ids conj dispatch-id))
        {:ok?         true
         :event       event-v
         :dispatch-id dispatch-id
         ;; Resolved by dispatch-and-collect / collect-after-dispatch.
         :epoch-id    nil
         :note        (if dispatch-id
                        "10x's epoch lands after the trace-debounce (~50ms); resolve via dispatch-and-collect or collect-after-dispatch with :dispatch-id."
                        "re-frame predates rf-3p7 (commit af024c3) — :dispatch-id auto-generation not available; correlation by :dispatch-id won't work.")})
      (catch :default e
        ;; Surface the throw on the console-log buffer too, tagged
        ;; :handler-error, so console-tail picks it up alongside
        ;; the structured response. Stack matters for these.
        (let [stack (try (.-stack e) (catch :default _ ""))]
          (append-console-entry!
           :error
           [(str "[handler-threw] " (or (ex-message e) (str e)))
            (str event-v)]
           stack
           :handler-error))
        ;; Stringify ex-data — it can carry JS object refs (interceptor
        ;; records, ratoms) that don't edn-roundtrip back to the bb shim.
        {:ok?        false
         :reason     :handler-threw
         :event      event-v
         :error      (or (ex-message e) (str e))
         :error-data (when-let [d (ex-data e)] (pr-str d))}))
    (finally
      (reset! current-who :app))))

(defn last-claude-epoch
  "Most recent epoch dispatched by the skill in this session. Resolves
   by walking the native-epoch-buffer first (newest-first) for the
   first epoch whose `:dispatch-id` appears in `claude-dispatch-ids`,
   then falls back to 10x's buffer when re-frame predates rf-ybv or
   the dispatch-id has aged out of the native buffer."
  []
  (let [ours        @claude-dispatch-ids
        from-native (some->> (native-epochs)
                             reverse
                             (some (fn [raw]
                                     (when (contains? ours (:dispatch-id raw))
                                       raw)))
                             coerce-native-epoch)]
    (or from-native
        (when (or (ten-x-loaded?)
                  (not @native-epoch-cb-installed?))
          (some->> (read-10x-epochs)
                   reverse
                   (some (fn [raw]
                           (let [evt-trace   (find-trace raw :event)
                                 dispatch-id (-> evt-trace :tags :dispatch-id)]
                             (when (contains? ours dispatch-id) raw))))
                   coerce-epoch)))))

(def ^:private trace-debounce-settle-ms
  "How long we wait after dispatch-sync before reading 10x's epoch
   buffer. re-frame.trace debounces callback delivery (~50ms); 10x's
   `::receive-new-traces` then runs an event to populate
   `:epochs :matches-by-id`. Plus one render frame for `:render` traces
   to flush. 80ms is comfortably past both."
  80)

(defn- find-epoch-by-dispatch-id
  "Walk 10x's epoch buffer (newest-first; we expect the match within
   the most-recent few entries after a dispatch-sync) for the epoch
   whose event-trace carries the given dispatch-id. nil if not yet
   landed."
  [dispatch-id matches]
  (some (fn [m]
          (when (= dispatch-id (-> (find-trace m :event) :tags :dispatch-id))
            m))
        (reverse matches)))

(defn- chained-dispatch-ids
  "Vec of dispatch-ids whose event-trace carries this dispatch-id as
   `:parent-dispatch-id` — i.e. children fired via `:fx [:dispatch ...]`
   from within the parent's handler. Walks `matches` in chronological
   order so the returned vec preserves dispatch order."
  [parent-id matches]
  (->> matches
       (keep (fn [m]
               (let [tags (:tags (find-trace m :event))]
                 (when (= parent-id (:parent-dispatch-id tags))
                   (:dispatch-id tags)))))
       vec))

(defn dispatch-and-collect
  "dispatch-sync the event, wait for the trace debounce + a render
   frame so renders land in 10x's match-info, then resolve the epoch
   produced via :dispatch-id correlation.

   Returns a JS Promise — the shim awaits. Resolves to
   `{:ok? true :epoch-id ... :epoch ...}` or
   `{:ok? false :reason ... :event ...}`."
  [event-v]
  (js/Promise.
   (fn [resolve _reject]
     (let [{:keys [dispatch-id]} (tagged-dispatch-sync! event-v)
           settle (fn settle []
                    (js/requestAnimationFrame
                     (fn []
                       (let [matches (read-10x-epochs)
                             ours-m  (when dispatch-id
                                       (find-epoch-by-dispatch-id dispatch-id matches))]
                         (if ours-m
                           (resolve (clj->js
                                     {:ok?         true
                                      :dispatch-id dispatch-id
                                      :epoch-id    (match-id ours-m)
                                      :epoch       (coerce-epoch ours-m)}))
                           (resolve (clj->js
                                     {:ok?         false
                                      :reason      :no-new-epoch
                                      :event       event-v
                                      :dispatch-id dispatch-id
                                      :hint        "10x did not append a match for this :dispatch-id within the debounce + 1 frame. Possible causes: trace-enabled? false; handler threw before tracing finished; tab throttled; or re-frame predates rf-3p7 (no :dispatch-id generated)."})))))))]
       (js/setTimeout settle trace-debounce-settle-ms)))))

(defn collect-after-dispatch
  "Companion to `tagged-dispatch-sync!`: after a bash-side wait past
   the trace-debounce, resolve the epoch by `:dispatch-id` correlation
   (rf-3p7 / af024c3 in re-frame core) and return its coerced form
   plus any chained children fired via `:fx [:dispatch ...]`.

   The bash shim drives the wait — `dispatch-and-collect`'s JS
   Promise doesn't survive the cljs-eval round-trip back to babashka.

   Caller pattern (ops.clj's --trace path):
     1. cljs-eval `(tagged-dispatch-sync! ev)` → grab :dispatch-id
     2. Thread/sleep ~80ms (trace-debounce-settle-ms below)
     3. cljs-eval `(collect-after-dispatch <dispatch-id>)` → epoch

   Returns:
     {:ok? true :dispatch-id <id> :epoch-id <int> :epoch <coerced>
      :chained-dispatch-ids [<id> ...]}
     {:ok? false :reason :no-new-epoch :dispatch-id ... :hint ...}"
  [dispatch-id]
  (if (nil? dispatch-id)
    {:ok? false :reason :no-dispatch-id
     :hint "Pass the :dispatch-id returned by tagged-dispatch-sync!. nil here usually means re-frame predates rf-3p7 (commit af024c3) and didn't auto-generate one."}
    (let [matches (read-10x-epochs)
          ours-m  (find-epoch-by-dispatch-id dispatch-id matches)]
      (if ours-m
        (let [chain-ids (chained-dispatch-ids dispatch-id matches)]
          (cond-> {:ok?         true
                   :dispatch-id dispatch-id
                   :epoch-id    (match-id ours-m)
                   :epoch       (coerce-epoch ours-m)}
            (seq chain-ids) (assoc :chained-dispatch-ids chain-ids)))
        {:ok?         false
         :reason      :no-new-epoch
         :dispatch-id dispatch-id
         :hint        "10x has no match carrying this :dispatch-id. trace-enabled? may be false, the handler may have thrown before tracing finished, or the tab may be throttled."}))))

;; Forward-declared — defined in the dispatch-with bridge below.
;; dispatch-and-settle!'s :stub-fx-ids opt builds an overrides map
;; via this helper before forwarding to re-frame.core/dispatch-and-settle.
(declare build-stub-overrides)

;; ---------------------------------------------------------------------------
;; dispatch-and-settle! — rf-4mr bridge for the bash shim
;; ---------------------------------------------------------------------------
;;
;; re-frame core's `dispatch-and-settle` (rf-4mr, commit f8f0f59)
;; returns a Promise (CLJS) / clojure.core/promise (CLJ) that resolves
;; once the cascade of `:fx [:dispatch ...]` children has settled (an
;; adaptive quiet-period heuristic over the register-epoch-cb stream).
;;
;; The Promise can't round-trip through cljs-eval back to babashka.
;; `dispatch-and-settle!` here stores the eventual resolution in a
;; session-local atom keyed by an opaque handle; the bash shim polls
;; `await-settle <handle>` to read the settled record once it lands.
;;
;; Reconstituting from native-epoch-buffer rather than the Promise's
;; clj->js'd value: re-frame's resolve! walks the result with
;; `(clj->js v :keyword-fn name)`, which stringifies any keywords
;; inside (including :event, :coeffects, :effects). Reading from our
;; native buffer (which `register-epoch-cb` populates in lockstep —
;; both cbs are called from the same `tracing-cb-debounced` batch)
;; keeps keywords intact through the round-trip.

(defn- dispatch-and-settle-fn
  "JS-interop accessor for `re-frame.core/dispatch-and-settle` (rf-4mr,
   commit f8f0f59). Returns nil when re-frame predates rf-4mr — the JS
   symbol simply won't exist on that build.

   Same goog.global / aget-path strategy as `register-epoch-cb-fn`:
   keeps this file compiling against re-frame 1.4.5 (the runtime-test
   build's pinned dep), which doesn't ship the var."
  []
  (when-let [g (some-> js/goog .-global)]
    (aget-path g ["re_frame" "core" "dispatch_and_settle"])))

(defn collect-cascade-from-buffer
  "Collect the cascade rooted at `root-id` from a vec of raw native
   epochs. Walks `:parent-dispatch-id` chains starting from `root-id`
   and returns the coerced records.

   `(collect-cascade-from-buffer root-id)` reads the live buffer.
   `(collect-cascade-from-buffer root-id epochs)` takes raw epochs
   explicitly — public so tests can exercise the cascade walk against
   a synthetic buffer without standing up register-epoch-cb.

   Returns:
     {:root-epoch         <coerced-or-nil>
      :cascaded-epoch-ids [<id>...]
      :cascaded-epochs    [<coerced>...]}
   The root epoch is excluded from `:cascaded-epochs` /
   `:cascaded-epoch-ids`. Returns nil when `root-id` is nil."
  ([root-id] (collect-cascade-from-buffer root-id (native-epochs)))
  ([root-id epochs]
   (when root-id
     (let [;; Reachability closure — keep adding any epoch's dispatch-id
           ;; whose parent is already in the set, until fixed point.
           ids (loop [acc #{root-id}]
                 (let [grown (into acc
                                   (keep #(let [pid (:parent-dispatch-id %)
                                                id  (:dispatch-id %)]
                                            (when (and pid (contains? acc pid))
                                              id))
                                         epochs))]
                   (if (= grown acc) acc (recur grown))))
           root-raw      (some #(when (= root-id (:dispatch-id %)) %) epochs)
           cascaded-raws (filterv #(and (contains? ids (:dispatch-id %))
                                        (not= root-id (:dispatch-id %)))
                                  epochs)]
       {:root-epoch         (some-> root-raw coerce-native-epoch)
        :cascaded-epoch-ids (mapv :dispatch-id cascaded-raws)
        :cascaded-epochs    (mapv coerce-native-epoch cascaded-raws)}))))

(defonce settle-pending
  ;; handle-uuid -> {:settled? bool ... result fields}. Exposed (no
  ;; ^:private) so the runtime-test build can reset / inspect without
  ;; warnings, in line with native-epoch-buffer / claude-dispatch-ids.
  (atom {}))

(defn dispatch-and-settle!
  "Wrapper around `re-frame.core/dispatch-and-settle` (rf-4mr) for the
   bash shim. Dispatches `event-v` synchronously, awaits the cascade
   of `:fx [:dispatch ...]` children using re-frame core's adaptive
   quiet-period heuristic, and stores the settled record in a
   session-local atom keyed by an opaque handle.

   The bash shim polls `await-settle` to recover the resolved record
   once re-frame's Promise has settled — Promises don't round-trip
   through cljs-eval. See `await-settle` for the polling protocol.

   `current-who` flips :claude for the duration of the synchronous
   `dispatch-sync` that runs inside `dispatch-and-settle` (matches
   tagged-dispatch-sync!'s behavior). The root :dispatch-id is
   captured immediately after dispatch-sync via `recent-dispatch-id`
   and accumulated into `claude-dispatch-ids` so `last-claude-epoch`
   keeps pointing at our most recent dispatch.

   The settled record is reconstituted from the native-epoch-buffer
   rather than the Promise's clj->js'd value (see the long comment
   above the section for why).

   `opts` forwards to re-frame.core/dispatch-and-settle. Defaults:
   :timeout-ms 5000, :settle-window-ms 100, :include-cascaded? true.
   Two extra opts (consumed here, stripped before forwarding):
     :stub-fx-ids — vec of fx-id keywords; record-only stubs swap in
                    via `build-stub-overrides` for the duration of
                    the cascade. Stubbed effect values land in
                    `stub-effect-log`.
     :overrides   — explicit `{fx-id stub-fn}` map (rf-ge8). Wins
                    over `:stub-fx-ids` if both supplied. Use this
                    when you need a real stub fn rather than the
                    record-only behavior.

   Returns synchronously:
     {:ok? true :handle <uuid> :event ev :dispatch-id <id> :pending? true
      :stubbed-fx-ids [...]?}
     {:ok? false :reason :dispatch-and-settle-unavailable :hint ...}
     {:ok? false :reason :handler-threw :error ... :event ev}"
  ([event-v] (dispatch-and-settle! event-v {}))
  ([event-v opts]
   (if-let [d-and-s (dispatch-and-settle-fn)]
     (let [handle      (str (random-uuid))
           overrides   (or (:overrides opts)
                           (when-let [ids (seq (:stub-fx-ids opts))]
                             (build-stub-overrides ids)))
           settle-opts (dissoc opts :overrides :stub-fx-ids)
           ;; rf-ge8 reads :re-frame/fx-overrides off event meta inside
           ;; do-fx-after; meta survives (dispatch-sync event) so the
           ;; cascade picks it up.
           event-meta  (cond-> event-v
                         overrides (vary-meta assoc :re-frame/fx-overrides overrides))]
       (reset! current-who :claude)
       (try
         (let [p                (d-and-s event-meta settle-opts)
               root-dispatch-id (recent-dispatch-id)]
           (when root-dispatch-id
             (swap! claude-dispatch-ids conj root-dispatch-id))
           (swap! settle-pending assoc handle
                  {:settled?    false
                   :started-at  (js/Date.now)
                   :event       event-v
                   :dispatch-id root-dispatch-id})
           (-> p
               (.then (fn [js-result]
                        (let [raw     (js->clj js-result :keywordize-keys true)
                              ok?     (boolean (:ok? raw))
                              cascade (when (and ok? root-dispatch-id)
                                        (collect-cascade-from-buffer root-dispatch-id))]
                          (swap! settle-pending update handle merge
                                 (cond-> {:settled?           true
                                          :ok?                ok?
                                          :event              event-v
                                          :dispatch-id        root-dispatch-id
                                          :epoch-id           (some-> cascade :root-epoch :id)
                                          :epoch              (some-> cascade :root-epoch)
                                          :cascaded-epoch-ids (or (:cascaded-epoch-ids cascade) [])
                                          :cascaded-epochs    (or (:cascaded-epochs cascade) [])}
                                   (not ok?) (assoc :reason (:reason raw)))))))
               (.catch (fn [err]
                         (swap! settle-pending update handle merge
                                {:settled? true
                                 :ok?      false
                                 :reason   :promise-rejected
                                 :error    (str err)
                                 :event    event-v}))))
           (cond-> {:ok?         true
                    :handle      handle
                    :event       event-v
                    :dispatch-id root-dispatch-id
                    :pending?    true}
             overrides (assoc :stubbed-fx-ids (vec (sort (keys overrides))))))
         (catch :default e
           (swap! settle-pending dissoc handle)
           (let [stack (try (.-stack e) (catch :default _ ""))]
             (append-console-entry!
              :error
              [(str "[handler-threw] " (or (ex-message e) (str e)))
               (str event-v)]
              stack
              :handler-error))
           {:ok?        false
            :reason     :handler-threw
            :event      event-v
            :error      (or (ex-message e) (str e))
            :error-data (when-let [d (ex-data e)] (pr-str d))})
         (finally
           (reset! current-who :app))))
     {:ok?    false
      :reason :dispatch-and-settle-unavailable
      :hint   "re-frame predates rf-4mr (commit f8f0f59) — fall back to tagged-dispatch-sync! + collect-after-dispatch."})))

(defn await-settle
  "Read the settle-pending atom for `handle`. Used by the bash shim's
   polling loop to recover the result of a `dispatch-and-settle!`.

   Returns:
     - settled, success: {:settled? true :ok? true :event ev
                          :dispatch-id <id> :epoch-id <id>
                          :epoch <coerced> :cascaded-epoch-ids [...]
                          :cascaded-epochs [...]}
     - settled, timeout: {:settled? true :ok? false :reason :timeout
                          :event ev}
     - still pending:    {:settled? false :pending? true :handle h}
     - unknown handle:   {:settled? false :reason :unknown-handle :handle h}

   On a settled response, the handle is removed from the atom — pollers
   should not call await-settle on the same handle twice."
  [handle]
  (if-let [entry (get @settle-pending handle)]
    (if (:settled? entry)
      (do (swap! settle-pending dissoc handle)
          entry)
      {:settled? false :pending? true :handle handle})
    {:settled? false :reason :unknown-handle :handle handle}))

;; ---------------------------------------------------------------------------
;; dispatch-with bridge — rf-ge8 (fx-overrides) for safe iteration
;; ---------------------------------------------------------------------------
;;
;; re-frame core's `dispatch-with` (rf-ge8, commit 2651a30) tags an
;; event with `:re-frame/fx-overrides` meta; `do-fx-after` reads the
;; meta and binds `*current-overrides*` for that event's fx execution
;; (and its synchronous cascade — `tag-with-fx-overrides` propagates
;; the meta to children queued via `:fx [:dispatch ...]`).
;;
;; Why this matters for the shim: the experiment-loop recipe in
;; SKILL.md leans on `undo-step-back` to rewind app-db between probe
;; dispatches. That works for db state but does nothing for already-
;; fired side effects (HTTP request landed, URL changed, local-storage
;; mutated). With dispatch-with the agent stubs the side-effecting fx
;; for the duration of a single probe — no global state to restore.
;;
;; The bash shim drives this via `--stub :http-xhrio` (or several
;; `--stub` flags). Each named fx-id gets `record-only-stub` slotted
;; in: the captured effect value lands in `stub-effect-log`, the
;; original handler doesn't fire. `stubbed-effects-since` reads the
;; log incrementally; `clear-stubbed-effects!` resets it.
;;
;; Custom (non-record-only) stubs that need real fn bodies must use
;; `dispatch-with!` directly via `eval-cljs.sh` — fns can't round-trip
;; cljs-eval, so the CLI shorthand is record-only by design.

(defn- dispatch-with-fn
  "JS-interop accessor for `re-frame.core/dispatch-with` (rf-ge8,
   commit 2651a30). Returns nil when re-frame predates rf-ge8.
   Same goog.global / aget-path strategy as dispatch-and-settle-fn."
  []
  (when-let [g (some-> js/goog .-global)]
    (aget-path g ["re_frame" "core" "dispatch_with"])))

(defn- dispatch-sync-with-fn
  "JS-interop accessor for `re-frame.core/dispatch-sync-with` (rf-ge8)."
  []
  (when-let [g (some-> js/goog .-global)]
    (aget-path g ["re_frame" "core" "dispatch_sync_with"])))

(defonce stub-effect-log
  ;; Vec of {:fx-id kw :value any :ts ms :who kw} entries — every
  ;; record-only-stub invocation lands here. Exposed (no ^:private)
  ;; so the runtime-test build can reset / inspect without warnings.
  (atom []))

(defn record-only-stub
  "Build a record-only stub for `fx-id`: a 1-arg fn that captures its
   value into `stub-effect-log` and returns nil. The original fx's
   side-effect (HTTP, navigation, etc.) is suppressed.

   Public so callers building a custom dispatch-with override map can
   reuse the same logging strategy for some fx-ids while supplying a
   real stub for others."
  [fx-id]
  (fn [value]
    (swap! stub-effect-log conj
           {:fx-id fx-id
            :value value
            :ts    (js/Date.now)
            :who   @current-who})
    nil))

(defn build-stub-overrides
  "Convert a vec of fx-id keywords into a `{fx-id record-only-stub}`
   map suitable for `dispatch-with`. Public for tests."
  [fx-ids]
  (into {} (for [k fx-ids] [k (record-only-stub k)])))

(defn stubbed-effects-since
  "Slice of `stub-effect-log` with `:ts >= since-ts`. Returns
   `{:ok? true :entries [...] :now <ms>}`. Pass back the `:now` from
   a previous call as the next `since-ts` for incremental tailing.

   Single-arity reads the entire log."
  ([] (stubbed-effects-since 0))
  ([since-ts]
   {:ok?     true
    :entries (vec (filter #(>= (:ts %) since-ts) @stub-effect-log))
    :now     (js/Date.now)}))

(defn clear-stubbed-effects!
  "Reset the stub-effect-log to empty. `{:ok? true}`."
  []
  (reset! stub-effect-log [])
  {:ok? true})

(defn dispatch-with!
  "Wrapper around `re-frame.core/dispatch-with` (rf-ge8). Queued
   dispatch with selected fx handlers temporarily substituted for
   the duration of THIS event and any synchronous `:fx [:dispatch ...]`
   cascade.

   `current-who` flips :claude for the synchronous portion; the
   handler runs out of band, so handler-side console output tags
   :app (mirror of `tagged-dispatch!`).

   `overrides` is a `{fx-id stub-fn}` map. Use `build-stub-overrides`
   for record-only stubs.

   :reason :dispatch-with-unavailable when re-frame predates rf-ge8."
  [event-v overrides]
  (if-let [d-with (dispatch-with-fn)]
    (do
      (reset! current-who :claude)
      (try
        (d-with event-v overrides)
        {:ok?            true
         :queued?        true
         :event          event-v
         :stubbed-fx-ids (vec (sort (keys overrides)))}
        (finally (reset! current-who :app))))
    {:ok?    false
     :reason :dispatch-with-unavailable
     :hint   "re-frame predates rf-ge8 (commit 2651a30) — upgrade or use a global stub."}))

(defn dispatch-sync-with!
  "Wrapper around `re-frame.core/dispatch-sync-with` (rf-ge8). Same
   override semantics as `dispatch-with!` but synchronous: captures
   the auto-generated `:dispatch-id` from the trace stream so callers
   can correlate the eventual epoch (matches `tagged-dispatch-sync!`).

   Handler errors are caught and surfaced as
   `{:ok? false :reason :handler-threw ...}` (same shape as
   tagged-dispatch-sync!), and a `:handler-error`-tagged entry is
   appended to `console-log`."
  [event-v overrides]
  (if-let [d-sync-with (dispatch-sync-with-fn)]
    (do
      (reset! current-who :claude)
      (try
        (try
          (d-sync-with event-v overrides)
          (let [dispatch-id (recent-dispatch-id)]
            (when dispatch-id (swap! claude-dispatch-ids conj dispatch-id))
            {:ok?            true
             :event          event-v
             :dispatch-id    dispatch-id
             :epoch-id       nil
             :stubbed-fx-ids (vec (sort (keys overrides)))})
          (catch :default e
            (let [stack (try (.-stack e) (catch :default _ ""))]
              (append-console-entry!
               :error
               [(str "[handler-threw] " (or (ex-message e) (str e)))
                (str event-v)]
               stack
               :handler-error))
            {:ok?        false
             :reason     :handler-threw
             :event      event-v
             :error      (or (ex-message e) (str e))
             :error-data (when-let [d (ex-data e)] (pr-str d))}))
        (finally (reset! current-who :app))))
    {:ok?    false
     :reason :dispatch-sync-with-unavailable
     :hint   "re-frame predates rf-ge8 (commit 2651a30) — upgrade or use a global stub."}))

(defn dispatch-with-stubs!
  "Convenience: `dispatch-with!` with record-only stubs for each fx-id
   in `fx-ids`. The bash shim's `--stub <fx-id>` flag drives this —
   passing keywords across cljs-eval is straightforward where passing
   fns is not."
  [event-v fx-ids]
  (dispatch-with! event-v (build-stub-overrides fx-ids)))

(defn dispatch-sync-with-stubs!
  "`dispatch-sync-with!` counterpart of `dispatch-with-stubs!`."
  [event-v fx-ids]
  (dispatch-sync-with! event-v (build-stub-overrides fx-ids)))

;; ---------------------------------------------------------------------------
;; re-com awareness
;; ---------------------------------------------------------------------------

(defn re-com?
  "True if the component name names a re-com component. Public so
   tests can exercise the heuristic directly."
  [component-name]
  (and (string? component-name)
       (str/starts-with? component-name "re-com.")))

(defn re-com-category
  "Classify a re-com component by ns segment. Rough — enough to let
   recipes answer 'which inputs re-rendered'. Public for tests.

   Categories follow the re-com source layout (current as of 2026-04):
   layout boxes, inputs (buttons, dropdowns, selection lists, etc.),
   tables, and content (text/typography/throbbers/popovers/etc.)."
  [component-name]
  (cond
    (not (re-com? component-name))                            nil
    (re-find #"re-com\.box"                  component-name)  :layout
    (re-find #"re-com\.gap"                  component-name)  :layout
    (re-find #"re-com\.scroller"             component-name)  :layout
    (re-find #"re-com\.splits"               component-name)  :layout
    (re-find #"re-com\.modal-panel"          component-name)  :layout
    (re-find #"re-com\.buttons"              component-name)  :input
    (re-find #"re-com\.checkbox"             component-name)  :input
    (re-find #"re-com\.radio-button"         component-name)  :input
    (re-find #"re-com\.input-text"           component-name)  :input
    (re-find #"re-com\.input-time"           component-name)  :input
    (re-find #"re-com\.dropdown"             component-name)  :input
    (re-find #"re-com\.single-dropdown"      component-name)  :input
    (re-find #"re-com\.tag-dropdown"         component-name)  :input
    (re-find #"re-com\.selection-list"       component-name)  :input
    (re-find #"re-com\.multi-select"         component-name)  :input
    (re-find #"re-com\.tree-select"          component-name)  :input
    (re-find #"re-com\.typeahead"            component-name)  :input
    (re-find #"re-com\.datepicker"           component-name)  :input
    (re-find #"re-com\.daterange"            component-name)  :input
    (re-find #"re-com\.slider"               component-name)  :input
    (re-find #"re-com\.tabs"                 component-name)  :input
    (re-find #"re-com\.bar-tabs"             component-name)  :input
    (re-find #"re-com\.pill-tabs"            component-name)  :input
    (re-find #"re-com\.horizontal-tabs"      component-name)  :input
    (re-find #"re-com\.simple-v-table"       component-name)  :table
    (re-find #"re-com\.v-table"              component-name)  :table
    (re-find #"re-com\.nested-grid"          component-name)  :table
    (re-find #"re-com\.table-filter"         component-name)  :table
    :else                                                     :content))

(defn classify-render-entry
  "Annotate a render entry with :re-com? and :re-com/category."
  [{:keys [component] :as entry}]
  (let [cat (re-com-category component)]
    (cond-> entry
      (re-com? component) (assoc :re-com? true)
      cat                  (assoc :re-com/category cat))))

;; ---------------------------------------------------------------------------
;; DOM ↔ source bridge (re-com `:src`)
;; ---------------------------------------------------------------------------
;;
;; Prerequisites: re-com debug instrumentation enabled, call sites
;; pass `:src (at)`. See docs/initial-spec.md §4.3b.

(defn parse-rc-src
  "Parse re-com's `data-rc-src` attribute into {:file :line}.
   Returns nil on malformed input.

   re-com emits the attribute as a single 'file:line' string from
   `re-com.debug` (see `(str file \":\" line)` at debug.cljs:83). No
   column component, despite Clojure's `(at)` macro carrying one — re-com
   discards it before serialising. Public for tests."
  [attr-val]
  (when (and (string? attr-val) (seq attr-val))
    (let [idx (str/last-index-of attr-val ":")]
      (when (and idx (pos? idx))
        (let [file-part (subs attr-val 0 idx)
              line-part (subs attr-val (inc idx))]
          (when (re-matches #"\d+" line-part)
            {:file file-part
             :line (js/parseInt line-part 10)}))))))

(defn re-com-debug-enabled?
  "Heuristic: re-com debug is enabled if any DOM element carries a
   `data-rc-src` attribute. Public so `discover-app.sh` can surface
   it in the health report.

   TODO verify the definitive gate in `re-com.config` in the spike;
   this heuristic is fine when the app has rendered at least once but
   may misreport on a freshly-loaded page.

   Returns false in non-browser environments (no `js/document`)."
  []
  (boolean
    (and (exists? js/document)
         (some? (.querySelector js/document "[data-rc-src]")))))

;; Last-clicked capture — passive listener that records the element
;; most recently clicked anywhere on the page. Installed once by
;; `install-last-click-capture!` during injection so ops like
;; `dom/source-at :last-clicked` have something to resolve.

(defonce ^:private last-clicked (atom nil))

(defn install-last-click-capture!
  "Install a single capturing click listener on document that records
   the most recently clicked element. Idempotent — calling twice does
   not double-register (guard via a marker on window).

   Silent no-op when there is no browser-side `js/window` /
   `js/document` (e.g. shadow-cljs's `:node-test` build)."
  []
  (when (and (exists? js/window)
             (exists? js/document)
             (not (aget js/window "__rfp_click_capture__")))
    (aset js/window "__rfp_click_capture__" true)
    (.addEventListener
     js/document
     "click"
     (fn [e] (reset! last-clicked (.-target e)))
     #js {:capture true :passive true})))

(defn last-clicked-element
  "Return the DOM element most recently clicked, or nil if nothing has
   been clicked yet this session. Driven by `install-last-click-capture!`."
  []
  @last-clicked)

(defn- selector-or-last-clicked [selector]
  "If selector is `:last-clicked` (or the string equivalent), return
   the last-clicked element. Otherwise resolve via querySelector."
  (cond
    (or (= selector :last-clicked) (= selector "last-clicked"))
    (last-clicked-element)

    (string? selector)
    (.querySelector js/document selector)

    :else nil))

(defn dom-source-at
  "Given a CSS selector (or `:last-clicked` / `\"last-clicked\"`),
   return the `:src` {:file :line} attached by re-com's debug path.
   Returns a structured result."
  [selector]
  (if-let [el (selector-or-last-clicked selector)]
    (if-let [src-attr (.getAttribute el "data-rc-src")]
      {:ok? true :src (parse-rc-src src-attr) :selector selector}
      {:ok? true :src nil :selector selector
       :reason (if (re-com-debug-enabled?)
                 :no-src-at-this-element
                 :re-com-debug-disabled)})
    {:ok? false :reason :no-element :selector selector
     :hint (when (or (= selector :last-clicked) (= selector "last-clicked"))
             "Nothing clicked this session; interact with the page first, or pass a CSS selector instead.")}))

(defn- src-pattern-matches?
  "True if the element's `data-rc-src` attribute contains
   `file:line`. Used by `dom-find-by-src` and `dom-fire-click`. We
   pull every `[data-rc-src]` and compare strings rather than
   building a CSS selector with the file embedded — single quotes,
   spaces, brackets etc. in real-world paths break the
   string-interpolated selector and there's no portable escape that
   covers every case (`CSS.escape` exists but isn't always available
   in older webviews and doesn't escape `'` itself for attribute
   selectors)."
  [el pattern]
  (when-let [v (.getAttribute el "data-rc-src")]
    (str/includes? v pattern)))

(defn dom-find-by-src
  "Find live DOM elements whose `data-rc-src` matches file+line.
   Returns a list of {:selector :src :tag} summaries."
  [file line]
  (let [pattern (str file ":" line)
        nodes   (.querySelectorAll js/document "[data-rc-src]")]
    (->> (array-seq nodes)
         (filter #(src-pattern-matches? % pattern))
         (mapv (fn [node]
                 {:tag   (.toLowerCase (.-tagName node))
                  :id    (not-empty (.-id node))
                  :class (not-empty (.-className node))
                  :src   (parse-rc-src (.getAttribute node "data-rc-src"))})))))

(defn dom-fire-click
  "Synthesise a click on the element matching file+line. Picks the
   first match if multiple. Returns the epoch produced (if any)."
  [file line]
  (let [pattern (str file ":" line)
        nodes   (array-seq (.querySelectorAll js/document "[data-rc-src]"))
        el      (first (filter #(src-pattern-matches? % pattern) nodes))]
    (if el
      (let [before (latest-epoch-id)
            ev     (js/Event. "click" #js {:bubbles true :cancelable true})]
        (.dispatchEvent el ev)
        {:ok?           true
         :clicked       {:tag (.toLowerCase (.-tagName el))
                         :id  (not-empty (.-id el))}
         :epoch-before  before
         ;; The epoch lands asynchronously; caller should follow up
         ;; with `last-epoch` after a frame if they want it.
         })
      {:ok? false :reason :no-element-at-src :file file :line line})))

(defn dom-describe
  "Summarise a DOM element: its tag, id, classes, `data-rc-src`,
   and the names of event handlers React has attached."
  [selector]
  (if-let [el (.querySelector js/document selector)]
    {:ok?      true
     :tag      (.toLowerCase (.-tagName el))
     :id       (not-empty (.-id el))
     :class    (not-empty (.-className el))
     :src      (parse-rc-src (.getAttribute el "data-rc-src"))
     :text     (let [t (.-textContent el)]
                 (when (and t (< (count t) 200)) t))}
    {:ok? false :reason :no-element :selector selector}))

;; ---------------------------------------------------------------------------
;; Hot-reload probe support
;; ---------------------------------------------------------------------------

(defn registrar-handler-ref
  "Return an opaque identifier for the currently-registered handler
   of kind+id. Used as a pre/post-reload comparison: if the reference
   changes, the reload has taken effect."
  [kind id]
  (let [h (get-in @registrar/kind->id->handler [kind id])]
    (when h
      ;; Function refs aren't reliably `=`, so hash them as strings.
      (hash (str h)))))

;; ---------------------------------------------------------------------------
;; Watch predicate matching
;; ---------------------------------------------------------------------------

(defn epoch-matches?
  "Test a (coerced) epoch against a predicate map built from
   `watch-epochs.sh` CLI args.

   Prefix matching uses `str` on both sides so `:cart` matches
   `:cart/apply-coupon` (and `:cart/` matches `:cart/apply-coupon`
   when passed as a string from the shell). Keep this string-based
   to avoid depending on keyword lexer edge cases."
  [pred epoch]
  (let [{:keys [event-id event-id-prefix effects timing-ms touches-path sub-ran render]} pred
        ev (:event epoch)]
    (boolean
     (and
      (if event-id        (= event-id (first ev)) true)
      (if event-id-prefix (some-> (first ev) str (str/starts-with? (str event-id-prefix))) true)
      (if effects         (some #(= effects (:fx-id %)) (:effects/fired epoch)) true)
      (if timing-ms       ;; expects [:> n] or [:< n]
        (let [[op n] timing-ms]
          (case op
            :> (> (:time-ms epoch 0) n)
            :< (< (:time-ms epoch 0) n)
            true))
        true)
      (if touches-path
        (let [{:keys [only-before only-after]} (:app-db/diff epoch)]
          (if (empty? touches-path)
            ;; Empty path = "the root touched at all" — any non-empty
            ;; diff matches. Without this special-case, `(get-in nil
            ;; [])` returns nil and the predicate always fails for the
            ;; root path, which is surprising.
            (or (seq only-before) (seq only-after))
            (or (some? (get-in only-before touches-path))
                (some? (get-in only-after touches-path)))))
        true)
      (if sub-ran         (some #(= sub-ran (first (:query-v %))) (:subs/ran epoch)) true)
      (if render          (some #(= render (:component %)) (:renders epoch)) true)))))

;; ---------------------------------------------------------------------------
;; Time-travel adapter
;; ---------------------------------------------------------------------------
;;
;; 10x has no stable public undo API. We drive its internal epoch
;; navigation by dispatching into 10x's inlined re-frame instance.
;; The events live in `day8.re-frame-10x.navigation.epochs.events`:
;;
;;   ::previous     - move cursor one epoch back
;;   ::next         - move cursor one epoch forward
;;   ::most-recent  - move to head
;;   ::load <id>    - jump to a specific epoch id
;;   ::replay       - re-run the currently selected event
;;
;; Each navigation event also dispatches `::reset-current-epoch-app-db`,
;; which resets the *userland* re-frame.db/app-db to that epoch's
;; pre-event state — but ONLY when `[:settings :app-db-follows-events?]`
;; is true. That's the actual time-travel mechanism. The setting defaults
;; to true (loaded from local-storage with :or true); `undo-status`
;; reports its current value so callers can detect the off case.
;;
;; See docs/initial-spec.md §4.6 and Appendix A2 for the long-term
;; hardening path (a public `day8.re-frame-10x.public` namespace that
;; would replace this internal-coupling).

(def ^:private ten-x-events-ns
  "Fully-qualified namespace prefix for 10x's epoch-navigation events."
  "day8.re-frame-10x.navigation.epochs.events")

(def ^:private ten-x-settings-ns
  "Fully-qualified namespace prefix for 10x's settings events."
  "day8.re-frame-10x.panels.settings.events")

(defn- ten-x-event-kw [evt-ns local]
  (keyword evt-ns (name local)))

(defn- ten-x-dispatch!
  "Dispatch a vector into 10x's inlined re-frame instance. Returns
   {:ok? true} on success or {:ok? false :reason :ten-x-missing}."
  [event-v]
  (if-let [rf-core (ten-x-rf-core)]
    (let [dispatch-fn (aget rf-core "dispatch")]
      (try
        (dispatch-fn event-v)
        {:ok? true :event event-v}
        (catch :default e
          {:ok? false :reason :dispatch-threw
           :event event-v :message (.-message e)})))
    {:ok? false :reason :ten-x-missing}))

(defn- ten-x-state
  "Read 10x's epoch + settings state from its inlined app-db. Returns
   nil if 10x is not loaded."
  []
  (when-let [a (ten-x-app-db-ratom)]
    (let [db @a]
      {:match-ids               (get-in db [:epochs :match-ids] [])
       :selected-epoch-id       (get-in db [:epochs :selected-epoch-id])
       :app-db-follows-events?  (get-in db [:settings :app-db-follows-events?] true)})))

(defn- index-of [v x]
  (first (keep-indexed (fn [i y] (when (= x y) i)) v)))

(defn undo-status
  "Cursor and bounds for time-travel. Includes the
   :app-db-follows-events? setting so callers can detect when undo
   navigation will move the cursor but NOT reset the userland app-db."
  []
  (if-let [{:keys [match-ids selected-epoch-id app-db-follows-events?]} (ten-x-state)]
    (let [idx (when selected-epoch-id (index-of match-ids selected-epoch-id))]
      {:ok?                    true
       :selected-epoch-id      selected-epoch-id
       :total-epochs           (count match-ids)
       :back-count             (or idx 0)
       :forward-count          (if idx (max 0 (- (count match-ids) idx 1)) 0)
       :app-db-follows-events? app-db-follows-events?})
    {:ok? false :reason :ten-x-missing}))

(defn- check-follows-events
  "Return a warning shape if the setting is off; nil otherwise. Used by
   navigation ops so callers see the mismatch when their app-db doesn't
   move."
  []
  (when-let [{:keys [app-db-follows-events?]} (ten-x-state)]
    (when-not app-db-follows-events?
      {:warning :app-db-follows-events?-disabled
       :hint    "10x's :app-db-follows-events? setting is off — the cursor will move but app-db will NOT reset. Toggle it via 10x's UI or dispatch [:day8.re-frame-10x.panels.settings.events/app-db-follows-events? true] into 10x to enable."})))

(defn undo-step-back
  "Move 10x's cursor one epoch back, resetting userland app-db to that
   epoch's pre-event state (when the setting allows)."
  []
  (let [r (ten-x-dispatch! [(ten-x-event-kw ten-x-events-ns "previous")])]
    (cond-> r
      (:ok? r) (merge (or (check-follows-events) {})))))

(defn undo-step-forward
  "Move 10x's cursor one epoch forward."
  []
  (let [r (ten-x-dispatch! [(ten-x-event-kw ten-x-events-ns "next")])]
    (cond-> r
      (:ok? r) (merge (or (check-follows-events) {})))))

(defn undo-to-epoch
  "Jump 10x's cursor to a specific epoch id (must exist in the buffer)."
  [id]
  (let [present? (when-let [s (ten-x-state)] (some #(= id %) (:match-ids s)))]
    (if-not present?
      {:ok? false :reason :unknown-epoch-id :id id
       :hint "epoch id not in 10x's current buffer; use undo-status to see :match-ids"}
      (let [r (ten-x-dispatch! [(ten-x-event-kw ten-x-events-ns "load") id])]
        (cond-> r
          (:ok? r) (merge (or (check-follows-events) {})))))))

(defn undo-most-recent
  "Jump 10x's cursor to the most recent epoch (cancels any time-travel)."
  []
  (let [r (ten-x-dispatch! [(ten-x-event-kw ten-x-events-ns "most-recent")])]
    (cond-> r
      (:ok? r) (merge (or (check-follows-events) {})))))

(defn undo-replay
  "Re-dispatch the currently selected epoch's event. 10x first resets
   userland app-db to the epoch's pre-event state, waits for reagent
   quiescence, then re-fires the event."
  []
  (ten-x-dispatch! [(ten-x-event-kw ten-x-events-ns "replay")]))

;; ---------------------------------------------------------------------------
;; Version enforcement
;; ---------------------------------------------------------------------------
;;
;; Spec §3.7 says `discover-app.sh` must refuse to connect when a dep
;; is below its minimum floor. CLJS libs don't have a uniform
;; "version" convention in-browser, so this is a best-effort read:
;; try known var names / JS globals, return :unknown when nothing
;; matches. Floors are nil (no enforcement) until the spike confirms
;; where version info actually lives for each lib.

(def version-floors
  "Floor versions from spec §3.7. `nil` means 'no enforcement yet' —
   the check is plumbed through but does not reject. Only re-com
   currently exposes a runtime-readable version (via `re-com.config/version`,
   a `goog-define`); re-frame, re-frame-10x, and shadow-cljs do not.
   Floors stay nil for those until the libs add a public version var."
  {:re-frame       nil     ; no in-browser version var; spec placeholder "1.4"
   :re-frame-10x   nil     ; no in-browser version var; spec placeholder "1.9"
   :re-com         nil     ; readable via re-com.config/version; spec placeholder "2.20"
   :shadow-cljs    nil})   ; not a CLJS lib at runtime; spec placeholder "2.28"

(defn- read-version-of
  "Best-effort version lookup per lib. Returns a string like '2.20.0',
   or :unknown if we can't find it. Only re-com currently exposes a
   readable runtime version (`re-com.config/version`, a `goog-define`
   with empty default — populated only when the host build sets it via
   shadow-cljs `:closure-defines`)."
  [dep]
  (let [try-global (fn [& path]
                     (try
                       (let [g (some-> js/goog .-global)]
                         (reduce (fn [acc k] (when acc (aget acc k)))
                                 g path))
                       (catch :default _ nil)))]
    (or (case dep
          :re-com       (let [v (try-global "re_com" "config" "version")]
                          (when (and (string? v) (seq v)) v))
          :re-frame     nil      ; no public version var in-browser
          :re-frame-10x nil      ; no public version var in-browser
          :shadow-cljs  nil      ; not a CLJS runtime lib
          nil)
        :unknown)))

(defn version-below?
  "Compare observed to floor as dotted-number strings. Returns true if
   observed is strictly below floor. Returns false if either is
   :unknown or nil (can't enforce what we can't read).

   Public so tests can exercise it without setting up a live
   re-com.config/version goog-define.

   Implementation: pull digit runs from each side, zero-pad both
   sides to the same length, then compare. CLJS's `compare` on
   vectors compares LENGTHS first (unlike JVM Clojure's
   compare-indexed which compares elements first), so without padding
   `[2 20 0]` and `[2 21]` would order by `(> 3 2)` instead of
   `(< 20 21)`. Padding makes the comparison length-invariant."
  [observed floor]
  (and (string? observed) (string? floor)
       (let [->ints #(mapv (fn [s]
                             (let [n (js/parseInt s 10)]
                               (if (js/Number.isNaN n) 0 n)))
                           (re-seq #"\d+" %))
             obs    (->ints observed)
             flr    (->ints floor)
             width  (max (count obs) (count flr))
             pad    (fn [v] (vec (concat v (repeat (- width (count v)) 0))))]
         (neg? (compare (pad obs) (pad flr))))))

(defn version-report
  "Per-dep version read. Returned shape:
     {:by-dep            {:re-frame {:observed '1.4.0' :floor '1.4' :ok? true :enforced? true} ...}
      :all-ok?           true
      :enforcement-live? false     ;; true iff any dep has BOTH a
                                   ;;   non-nil floor AND a readable
                                   ;;   :observed version — otherwise
                                   ;;   the plumbing is in place but
                                   ;;   enforcement is effectively a
                                   ;;   no-op today
      :note              '...'}

   The code path always executes, but callers should not mistake
   `:all-ok? true` for 'versions have been checked'. When
   `:enforcement-live?` is false, `:all-ok?` is vacuously true."
  []
  (let [report (reduce
                (fn [m [dep floor]]
                  (let [observed (read-version-of dep)
                        bad?     (version-below? observed floor)]
                    (assoc m dep {:observed observed
                                  :floor    floor
                                  :ok?      (not bad?)
                                  :enforced? (and (string? observed)
                                                  (string? floor))})))
                {}
                version-floors)
        live?  (boolean (some :enforced? (vals report)))]
    {:by-dep            report
     :all-ok?           (every? :ok? (vals report))
     :enforcement-live? live?
     :note              (if live?
                          "Floors set for at least one dep; enforcement active."
                          "Floors are nil across the board — enforcement plumbed but effectively a no-op. See spec §8a spike item 'versions'.")}))

;; ---------------------------------------------------------------------------
;; Health check
;; ---------------------------------------------------------------------------

(defn health
  "One-call summary of the runtime's view of the world. Used by
   `discover-app.sh` to confirm the environment is healthy.

   Side effect: installs the last-clicked capture listener and the
   console capture wrapper if they aren't already installed. Both
   are idempotent."
  []
  (install-last-click-capture!)
  (install-console-capture!)
  (install-native-epoch-cb!)
  (install-native-trace-cb!)
  ;; epoch-count throws when 10x isn't loaded (or when running outside
  ;; the browser, e.g. shadow-cljs's node-test build). Health is meant
  ;; to be a best-effort summary; catch and fall back to nil so the
  ;; rest of the report still surfaces.
  (let [ec (try (epoch-count) (catch :default _ nil))]
    {:ok?                 true
     :session-id          session-id
     :ten-x-loaded?       (ten-x-loaded?)
     :trace-enabled?      re-frame.trace/trace-enabled?
     :re-com-debug?       (re-com-debug-enabled?)
     :last-click-capture? true
     :console-capture?    true
     :native-epoch-cb?    @native-epoch-cb-installed?
     :native-trace-cb?    @native-trace-cb-installed?
     :app-db-initialised? (map? @db/app-db)
     :versions            (version-report)
     :epoch-count         ec
     :claude-epoch-count  (count @claude-dispatch-ids)}))

;; ---------------------------------------------------------------------------
;; Session-bootstrap summary
;; ---------------------------------------------------------------------------

(defn value-shape-tag
  "Compact one-level-deep shape descriptor for app-summary. Returns
   a symbol naming the type without dragging the value itself into
   the response. Public so tests can exercise the dispatch directly."
  [v]
  (cond
    (nil? v)        'nil
    (map? v)        'map
    (vector? v)     'vec
    (set? v)        'set
    (sequential? v) 'seq
    (string? v)     'string
    (boolean? v)    'boolean
    (keyword? v)    'keyword
    (number? v)     'number
    :else           'other))

(defn app-summary
  "One-call session-bootstrap bundle. Returns versions, registrar
   inventory, live subs, app-db top-level keys + one-level shape,
   and the health map — saves 5+ separate ops at session start.

   Returned shape:
     {:ok?          true
      :versions     <version-report>
      :registrar    {:event [...] :sub [...] :fx [...] :cofx [...]}
      :live-subs    [<query-v> ...]
      :app-db-keys  [...]               ;; nil if app-db is not a map
      :app-db-shape {<key> <type-sym>}  ;; nil if app-db is not a map
      :health       <health map>
      :ts           <unix-ms>}"
  []
  (let [db @db/app-db]
    {:ok?          true
     :versions     (version-report)
     :registrar    (:by-kind (registrar-describe))
     :live-subs    (subs-live)
     :app-db-keys  (when (map? db) (vec (keys db)))
     :app-db-shape (when (map? db)
                     (into {} (map (fn [[k v]] [k (value-shape-tag v)])) db))
     :health       (health)
     :ts           (js/Date.now)}))
