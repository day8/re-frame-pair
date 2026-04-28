(ns re-frame-pair.runtime.registrar
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            [re-frame-pair.runtime.ten-x-adapter :as ten-x]))

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

(defn live-query-vs-fn
  "JS-interop accessor for `re-frame.core/live-query-vs` — re-frame's
   public, cache-shape-stable enumeration of currently-cached
   subscriptions (rf-5rpc, 2026 release). Returns nil when re-frame
   predates the public accessor; callers fall back to walking
   `re-frame.subs/query->reaction` directly via `extract-query-vs`.

   Routed through `goog.global` so this file still compiles against
   pre-rf-5rpc re-frame builds where the var simply isn't there."
  []
  (when-let [g (some-> js/goog .-global)]
    (ten-x/aget-path g ["re_frame" "core" "live_query_vs"])))

(defn subs-live
  "Query vectors currently held in re-frame's subscription cache.

   Prefers `re-frame.core/live-query-vs` (rf-5rpc) — the public,
   cache-shape-stable accessor. Falls back to walking the internal
   `re-frame.subs/query->reaction` cache atom when re-frame predates
   the public accessor; the cache-key shape that `extract-query-vs`
   unpacks is not a public contract."
  []
  (if-let [f (live-query-vs-fn)]
    (->> (f) (sort-by str) vec)
    (extract-query-vs (some-> subs/query->reaction deref keys))))

(defn subs-sample
  "Subscribe to query-v and deref once. See docs/initial-spec.md §4.1
   on caching/lifecycle — fine for one-shot probes, not for repeated
   polling outside a reactive context."
  [query-v]
  (try
    @(rf/subscribe query-v)
    (catch :default e
      {:ok? false :reason :sub-error :message (.-message e)})))

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
;; handler-source — call-site location of a registered handler
;;
;; Reads upstream re-frame's source-meta capture (rf-ysy, commit 15dfc25):
;; reg-event-db / reg-event-fx / reg-event-ctx / reg-sub / reg-fx are
;; macros that attach {:file :line} to the registered value via
;; with-meta. For events the meta lands on the interceptor chain (a
;; vector); for subs/fx it lands on the registered fn itself.
;;
;; `(meta (registrar/get-handler kind id))` returns the location
;; directly — no side-table, no opt-in registration macro needed.
;; The earlier local side-table (reg-event-db et al. macros in the
;; sibling .clj) was retired now that upstream rf-ysy dominates.
;;
;; Lives in this ns (rather than its own) because the facade has a
;; `handler-source` def that would clash with the simple name of an
;; `re-frame-pair.runtime.handler-source` ns.
;; ---------------------------------------------------------------------------

(defn handler-source
  "Source location of a registered handler, read from re-frame's
   own source-meta capture (rf-ysy, commit 15dfc25).

   Returns:
     {:ok? true :kind ... :id ... :file ... :line ... :column ... :source :fn-meta}
   or
     {:ok? false :reason :not-registered :kind ... :id ...}
     {:ok? false :reason :no-source-meta :kind ... :id ...}

   Implementation: re-frame's reg-* macros attach {:file :line} to the
   stored value via with-meta. For :event the stored value is the
   interceptor chain (a vector — vectors carry meta natively); for
   :sub / :fx the stored value is a fn, which carries meta via
   IObj-on-cljs.core/fn (CLJS) or MetaFn (CLJ).

   Returns :no-source-meta cleanly when re-frame predates rf-ysy or
   when registration went through a non-macro path (e.g. the
   reg-*-fn programmatic-registration variants that don't capture
   &form)."
  [kind id]
  (let [stored (registrar/get-handler kind id)
        m      (when stored (meta stored))]
    (cond
      (nil? stored)
      {:ok? false :reason :not-registered :kind kind :id id}

      (and m (or (:file m) (:line m)))
      {:ok?    true
       :kind   kind
       :id     id
       :file   (:file m)
       :line   (:line m)
       :column (:column m)
       :source :fn-meta}

      :else
      {:ok? false :reason :no-source-meta :kind kind :id id})))
