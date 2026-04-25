;;;; re-frame-pair.runtime — injected helper namespace
;;;;
;;;; This file is evaluated by `scripts/inject-runtime.sh` on first
;;;; connect. It creates the `re-frame-pair.runtime` namespace inside
;;;; the running browser app and populates it with helpers that the
;;;; skill's ops call through `eval-cljs.sh`.
;;;;
;;;; Design invariants (see docs/initial-spec.md):
;;;;   - No second `register-trace-cb`. All epoch data comes from
;;;;     re-frame-10x's existing trace infrastructure.
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
  "Best-known 10x-inlined re-frame version slugs. Tried first so the
   common case is one aget-path lookup. If none match, we fall back
   to enumerating whatever child keys live under
   `day8.re_frame_10x.inlined_deps.re_frame` at runtime — see
   `ten-x-inlined-rf`. Update this list opportunistically; the
   fallback handles fresh 10x releases on its own."
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

(defn- ten-x-inlined-rf
  "The JS object for 10x's inlined `re-frame` package.

   Strategy: try the known-version slugs first (cheap, deterministic),
   then fall back to enumerating every child key under
   `day8.re_frame_10x.inlined_deps.re_frame` and picking the first
   that has a `re_frame.db.app_db` underneath. The fallback means a
   fresh 10x release with a new slug works without any code change —
   `read-10x-epochs` no longer throws `:ten-x-missing` just because
   we shipped before the slug got added to the known list."
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
  "Full trace stream from 10x's internal app-db at `[:traces :all]`.
   This is `re-frame.trace`'s raw stream, with every op-type intact:
   `:event :sub/run :sub/create :sub/dispose :render :raf :raf-end
   :event/handler` etc. The skeleton stored at `(:match-info match)`
   only retains the subset that fits 10x's epoch start/end markers
   (event-run, fsm-trigger, end-of-match, sync) — for renders and
   sub-runs we have to come back here. Returns [] when 10x isn't
   loaded so callers can no-op gracefully."
  []
  (or (some-> (ten-x-app-db-ratom) deref :traces :all)
      []))

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
  "Raw matches from 10x's epoch buffer in chronological order.
   Throws ex-info with :reason :ten-x-missing if 10x is not loaded
   (or its inlined-deps version path isn't recognised).

   Each element is a 10x match record:
     {:match-info <vec-of-raw-traces> :sub-state <map> :timing <map>}
   Pass through `coerce-epoch` to translate to the §4.3a shape."
  []
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
                                  "10x itself probably isn't preloaded.")})))
    (let [db    @a
          ids   (get-in db [:epochs :match-ids] [])
          by-id (get-in db [:epochs :matches-by-id] {})]
      (mapv #(get by-id %) ids))))

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

(defn- sub-runs-from-state
  "§4.3a :subs/ran. 10x's `:match-info` doesn't carry `:sub/run` traces
   directly (`metam/parse-traces` strips them when building partitions);
   instead the post-epoch reaction state at
   `(-> match :sub-state :reaction-state)` records `:run? true` and
   `:order [:sub/run ...]` for each reaction that re-ran during the
   epoch. We pick those whose value *changed* — `:sub/traits
   :unchanged?` not set — and expose just the user-facing query-v.

   `:time-ms` is not stored per-sub at this layer; omit it rather than
   make it up. Total sub time is in :timing :animation-frame-subs if
   needed."
  [match]
  (->> (-> match :sub-state :reaction-state)
       (filter (fn [[_ sub]]
                 (and (some #{:sub/run} (:order sub))
                      (not (get-in sub [:sub/traits :unchanged?])))))
       (mapv (fn [[_ sub]] {:query-v (:subscription sub)}))))

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
   via the live DOM (§4.3b)."
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
        :subs/ran          (when render-src (sub-runs-from-state render-src))
        :subs/cache-hit    (when render-src (sub-cache-hits-from-state render-src))
        :renders           (when render-src (renders-from-traces render-src all-traces))}))))

(defn latest-epoch-id
  "Id of 10x's newest match, or nil if the buffer is empty / 10x is
   not loaded.

   Cheap path: 10x already keeps an ordered `:match-ids` vec at
   `[:epochs :match-ids]` in its app-db; the head of it IS what we
   want. Avoids `read-10x-epochs`'s full per-match map-rebuild —
   significant for `watch-epochs.sh`, which polls this at ~100ms cadence
   and used to construct a fresh 25-entry coerced-match vec every tick."
  []
  (when-let [a (ten-x-app-db-ratom)]
    (last (get-in @a [:epochs :match-ids]))))

(defn epoch-count
  "Total matches in 10x's ring buffer."
  []
  (count (read-10x-epochs)))

(defn epoch-by-id
  "Return the coerced epoch with matching id, or nil."
  [id]
  (->> (read-10x-epochs)
       (some #(when (= id (match-id %)) %))
       coerce-epoch))

(defn last-epoch
  "Most recently appended epoch, coerced. Nil if buffer is empty."
  []
  (some-> (read-10x-epochs) last coerce-epoch))

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
;; Claude-dispatch tagging
;; ---------------------------------------------------------------------------
;;
;; Event vectors can't carry metadata through re-frame (handlers
;; destructure positionally). Instead we track *the epoch ids our
;; dispatches produced* in a session-local set.

(defonce claude-epoch-ids
  (atom #{}))

(defn- remember-latest-epoch!
  "Record the current head-of-buffer id as a Claude-originated epoch."
  []
  (when-let [id (latest-epoch-id)]
    (swap! claude-epoch-ids conj id)
    id))

(defn tagged-dispatch!
  "Dispatch an event (queued) and record the resulting epoch's id in
   the Claude-originated set. Returns {:ok? true :epoch-id <id>}."
  [event-v]
  (rf/dispatch event-v)
  {:ok? true
   :queued? true
   :event event-v
   ;; Note: id not yet known — dispatch is queued, the epoch appears
   ;; once the handler runs. Callers that want the id should use
   ;; `tagged-dispatch-sync!` instead.
   :epoch-id nil})

(defn tagged-dispatch-sync!
  "`dispatch-sync` the event and capture the pre-dispatch head id so
   the caller can resolve the resulting epoch after the trace debounce
   completes.

   Why no synchronous epoch resolution: `re-frame.trace`'s callback
   delivery to 10x runs through a ~50ms debounce. The trace events for
   this dispatch are emitted synchronously, but 10x's `::receive-new-traces`
   event (which appends to `:epochs :matches-by-id`) doesn't fire until
   the debounce flushes. Reading `latest-epoch-id` immediately after
   `dispatch-sync` may or may not show the new epoch.

   Use `dispatch-and-collect` for the full async round-trip — it waits
   long enough for the debounce + an animation frame, then resolves the
   epoch and tags it. For raw fire-and-forget callers, the return value
   includes `:before-id` so they can poll `latest-epoch-id` themselves.

   Handler errors: re-frame's default error handler logs to console and
   re-throws the original exception, so a throwing handler would normally
   propagate out of `dispatch-sync` and back through `cljs-eval` as an
   nREPL `:err` — which then breaks the bb shim's edn parsing. We catch
   here and return a structured `:reason :handler-threw` instead, so the
   experiment-loop recipe (and dispatch.sh's --trace path) sees a clean
   failure shape."
  [event-v]
  (let [before-id (latest-epoch-id)]
    (try
      (rf/dispatch-sync event-v)
      {:ok?       true
       :event     event-v
       :before-id before-id
       ;; Resolved by dispatch-and-collect after frame/debounce wait.
       :epoch-id  nil
       :note      "10x's epoch lands after the trace-debounce (~50ms); resolve via dispatch-and-collect or poll latest-epoch-id."}
      (catch :default e
        ;; Stringify ex-data — it can carry JS object refs (interceptor
        ;; records, ratoms) that don't edn-roundtrip back to the bb shim.
        {:ok?        false
         :reason     :handler-threw
         :event      event-v
         :before-id  before-id
         :error      (or (ex-message e) (str e))
         :error-data (when-let [d (ex-data e)] (pr-str d))}))))

(defn last-claude-epoch
  "Most recent epoch that the skill dispatched in this session."
  []
  (let [ours @claude-epoch-ids]
    (->> (read-10x-epochs)
         reverse
         (some (fn [raw] (when (contains? ours (:id raw)) raw)))
         coerce-epoch)))

(def ^:private trace-debounce-settle-ms
  "How long we wait after dispatch-sync before reading 10x's epoch
   buffer. re-frame.trace debounces callback delivery (~50ms); 10x's
   `::receive-new-traces` then runs an event to populate
   `:epochs :matches-by-id`. Plus one render frame for `:render` traces
   to flush. 80ms is comfortably past both."
  80)

(defn dispatch-and-collect
  "dispatch-sync the event, wait for the trace debounce + a render
   frame so renders land in 10x's match-info, then resolve the epoch
   produced (and tag it as a Claude-originated epoch).

   Returns a JS Promise — the shim awaits. The promise's value is
   either {:ok? true :epoch-id ... :epoch ...} or a structured
   {:ok? false :reason ... :event ...} when the epoch never landed."
  [event-v]
  (js/Promise.
   (fn [resolve _reject]
     (let [{:keys [before-id]} (tagged-dispatch-sync! event-v)
           settle (fn settle []
                    (js/requestAnimationFrame
                     (fn []
                       (let [after-id (latest-epoch-id)]
                         (if (and after-id (not= before-id after-id))
                           (do (swap! claude-epoch-ids conj after-id)
                               (resolve (clj->js
                                         {:ok?      true
                                          :epoch-id after-id
                                          :epoch    (epoch-by-id after-id)})))
                           (resolve (clj->js
                                     {:ok?    false
                                      :reason :no-new-epoch
                                      :event  event-v
                                      :before-id before-id
                                      :after-id  after-id
                                      :hint   "10x did not append a new match within the debounce + 1 frame. The dispatch fired, but no trace landed — possible causes: trace-enabled? false, handler threw before tracing finished, or browser tab is throttled."}))))))) ]
       (js/setTimeout settle trace-debounce-settle-ms)))))

(defn collect-after-dispatch
  "Companion to `tagged-dispatch-sync!`: after a bash-side wait past
   the trace-debounce, find the FIRST new epoch (the one we slung,
   not whatever happens to be at head), tag it as Claude-originated,
   and return its coerced form. Any further epochs that landed after
   it (e.g. via `:fx [:dispatch ...]` chaining) are returned as
   `:chained-epoch-ids` for context.

   This is the synchronous equivalent of `dispatch-and-collect`'s
   post-await branch — split out so the bb shim can drive the wait
   itself (the JS Promise from `dispatch-and-collect` doesn't survive
   the cljs-eval round-trip back to babashka).

   Why first-after, not head: a `reg-event-fx` that returns
   `:fx [[:dispatch [:other-ev ...]]]` queues a follow-up event. Once
   the trace-debounce flushes, both the user event and the chained
   one(s) are in 10x's buffer. Sampling `latest-epoch-id` then would
   return the chained event's id — wrong: we want the event we slung.
   Walk the buffer for the first match-id > before-id instead.

   Caller pattern (in ops.clj's --trace path):
     1. cljs-eval `(tagged-dispatch-sync! ev)` → grab :before-id
     2. Thread/sleep ~80ms (trace-debounce-settle-ms below)
     3. cljs-eval `(collect-after-dispatch <before-id>)` → epoch + tag

   Returns:
     {:ok? true :epoch-id <id> :epoch <coerced> :chained-epoch-ids [...]}
     {:ok? false :reason :no-new-epoch :before-id ... :after-id ...}"
  [before-id]
  (let [matches  (read-10x-epochs)
        ;; First match strictly after before-id. If before-id is nil
        ;; (buffer was empty when we slung), the first match is ours.
        new-tail (if (nil? before-id)
                   matches
                   (drop-while #(<= (match-id %) before-id) matches))
        ours-match (first new-tail)
        ours-id    (some-> ours-match match-id)
        chain-ids  (mapv match-id (rest new-tail))]
    (if ours-id
      (do (swap! claude-epoch-ids conj ours-id)
          (cond-> {:ok?      true
                   :epoch-id ours-id
                   :epoch    (coerce-epoch ours-match)}
            (seq chain-ids) (assoc :chained-epoch-ids chain-ids)))
      {:ok?       false
       :reason    :no-new-epoch
       :before-id before-id
       :after-id  (some-> matches last match-id)
       :hint      "10x did not append a new match within the wait window. trace-enabled? may be false, the handler may have thrown before tracing finished, or the tab may be throttled."})))

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
   may misreport on a freshly-loaded page."
  []
  (some? (.querySelector js/document "[data-rc-src]")))

;; Last-clicked capture — passive listener that records the element
;; most recently clicked anywhere on the page. Installed once by
;; `install-last-click-capture!` during injection so ops like
;; `dom/source-at :last-clicked` have something to resolve.

(defonce ^:private last-clicked (atom nil))

(defn install-last-click-capture!
  "Install a single capturing click listener on document that records
   the most recently clicked element. Idempotent — calling twice does
   not double-register (guard via a marker on window)."
  []
  (when-not (aget js/window "__rfp_click_capture__")
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
   return the `:src` {:file :line :column} attached by re-com's debug
   path. Returns a structured result."
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

(defn- version-below?
  "Compare observed to floor as dotted-number strings. Returns true if
   observed is strictly below floor. Returns false if either is
   :unknown or nil (can't enforce what we can't read)."
  [observed floor]
  (and (string? observed) (string? floor)
       (let [->ints #(mapv (fn [s]
                             (let [n (js/parseInt s 10)]
                               (if (js/Number.isNaN n) 0 n)))
                           (re-seq #"\d+" %))]
         (neg? (compare (->ints observed) (->ints floor))))))

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

   Side effect: installs the last-clicked capture listener if it
   isn't already installed. Idempotent."
  []
  (install-last-click-capture!)
  {:ok?                 true
   :session-id          session-id
   :ten-x-loaded?       (ten-x-loaded?)
   :trace-enabled?      re-frame.trace/trace-enabled?
   :re-com-debug?       (re-com-debug-enabled?)
   :last-click-capture? true
   :app-db-initialised? (map? @db/app-db)
   :versions            (version-report)
   :epoch-count         (epoch-count)
   :claude-epoch-count  (count @claude-epoch-ids)})
