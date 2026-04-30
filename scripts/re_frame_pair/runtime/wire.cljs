(ns re-frame-pair.runtime.wire
  "Wire-safety for the cljs-eval transport channel.

   Every CLJS eval response that crosses the channel via
   `shadow.cljs.devtools.api/cljs-eval` is bounded by shadow-cljs's
   ~1MB printer cap. Past that ceiling the runtime returns
   `{:ok? true :value nil}` to the caller while the browser console
   throws `The limit of 1048576 bytes was reached while printing`.
   Asymmetric, silent — see issue #4 and bead rfp-zw3w.

   `return!` wraps a value for safe transport: trivial values pass
   through bare; non-trivial values are stashed in a session-keyed
   bounded LRU under a cursor, and the wire response carries an
   EDN-readable maybe-elided projection plus the elision metadata.
   Subsequent calls drill into the stash via `fetch-cursor` and
   `fetch-path`.

   The eval channel becomes a control channel — every transported
   value is wire-safe by construction, full data reconstituted on
   demand from the stash."
  (:require [re-frame-pair.runtime.session :as session]))

;; ---------------------------------------------------------------------------
;; Result store — session-keyed bounded LRU of full values
;; ---------------------------------------------------------------------------

(def ^:private default-max-store-size 1000)

(defonce ^:private result-store
  (atom {:entries  {}
         :order    []
         :counter  0
         :max-size default-max-store-size}))

(defn- alloc-cursor!
  "Allocate a fresh cursor of the form `<session-id>/<n>`. Monotonic
   per session; the session-id's UUID handles cross-session uniqueness."
  []
  (let [n (-> (swap! result-store update :counter inc) :counter)]
    (str session/session-id "/" n)))

(defn- store!
  "Stash `v` under `cursor` in the result store, evicting the oldest
   entry if the store is full. Insertion-order eviction (FIFO);
   re-promotion-on-access deferred until access patterns warrant LRU."
  [cursor v]
  (swap! result-store
         (fn [{:keys [entries order max-size] :as s}]
           (let [entries' (assoc entries cursor v)
                 order'   (conj order cursor)]
             (if (> (count entries') max-size)
               (let [drop-cursor (first order')]
                 (assoc s
                        :entries (dissoc entries' drop-cursor)
                        :order   (subvec order' 1)))
               (assoc s :entries entries' :order order'))))))

(defn fetch-cursor
  "Retrieve the full value previously stashed under `cursor`. Returns
   `nil` if the cursor was never allocated or has been evicted."
  [cursor]
  (get-in @result-store [:entries cursor]))

(defn fetch-path
  "Return `(get-in v path)` where `v` is the full value stashed under
   `cursor`. Returns `nil` if the cursor is gone or the path doesn't
   resolve."
  [cursor path]
  (when-let [v (fetch-cursor cursor)]
    (get-in v path)))

(defn release-cursor!
  "Drop a cursor from the result store. Optional — the LRU evicts
   automatically; this exists for tests and for operators who want
   to free a known-large value early."
  [cursor]
  (swap! result-store
         (fn [s]
           (-> s
               (update :entries dissoc cursor)
               (update :order   #(filterv (fn [c] (not= c cursor)) %))))))

(defn store-stats
  "Diagnostic: current entry count, monotonic counter, configured cap."
  []
  (let [{:keys [entries order counter max-size]} @result-store]
    {:count     (count entries)
     :order-len (count order)
     :counter   counter
     :max-size  max-size}))

(defn ^:no-doc reset-store!
  "Clear the result store. For tests only — production resets occur
   naturally on page refresh (the whole runtime re-injects)."
  []
  (swap! result-store assoc :entries {} :order [] :counter 0))

;; ---------------------------------------------------------------------------
;; Cheap byte estimator
;; ---------------------------------------------------------------------------

(defn ^:no-doc estimate-bytes
  "Rough estimate of `v`'s pr-str length without actually printing.
   Designed to be cheap and conservative — over-estimates are safer
   than under-estimates because they trigger elision sooner."
  [v]
  (cond
    (nil? v)      4
    (boolean? v)  5
    (number? v)   20
    (keyword? v)  (+ 1 (count (str v)))
    (symbol? v)   (count (str v))
    (string? v)   (+ 2 (count v))
    (map? v)      (transduce
                   (map (fn [[k vv]] (+ 2 (estimate-bytes k) (estimate-bytes vv))))
                   + 2 v)
    (set? v)      (transduce (map estimate-bytes) + 4 v)
    (coll? v)     (transduce (map estimate-bytes) + 2 v)
    :else         64))

;; ---------------------------------------------------------------------------
;; Type-aware shallow summarisation
;; ---------------------------------------------------------------------------

(defn- truncate
  "Truncate a string to `n` characters with an ellipsis marker."
  [s n]
  (if (and (string? s) (> (count s) n))
    (str (subs s 0 (max 0 (- n 3))) "...")
    s))

(defn- safe-pr-str
  "pr-str that swallows errors thrown by exotic print-method definitions."
  [v]
  (try (pr-str v) (catch :default _ "<unprintable>")))

(defn ^:no-doc shallow-summary
  "Type-aware structural summary of `v` that doesn't descend into
   children. Used at elision sites so the operator sees what was
   dropped at each path."
  [v sample-size]
  (cond
    (map? v)
    {:type        :map
     :count       (count v)
     :sample-keys (vec (take sample-size (keys v)))}

    (vector? v)
    {:type   :vec
     :count  (count v)
     :sample (mapv #(truncate (safe-pr-str %) 60) (take sample-size v))}

    (set? v)
    {:type  :set
     :count (count v)}

    (seq? v)
    {:type :seq}

    (string? v)
    {:type    :string
     :length  (count v)
     :preview (truncate v 64)}

    (fn? v)
    {:type :fn
     :name (truncate (safe-pr-str v) 80)}

    :else
    {:type    :other
     :preview (truncate (safe-pr-str v) 64)}))

;; ---------------------------------------------------------------------------
;; Trivial fast path
;; ---------------------------------------------------------------------------

(def ^:private trivial-bytes-threshold 1024)

(defn ^:no-doc trivial?
  "True when `v`'s printed form is small enough to ship bare —
   skipping cursor allocation, walking, and summarisation entirely.

   Conservative: false-negatives just take the slow path (one extra
   cursor + one walk). False-positives would smuggle a large value
   through unwrapped and risk shadow-cljs's printer cap, so the
   estimate is an upper bound."
  [v]
  (cond
    (nil? v)     true
    (boolean? v) true
    (number? v)  true
    (keyword? v) true
    (symbol? v)  true
    (string? v)  (< (count v) (- trivial-bytes-threshold 2))
    (and (counted? v) (zero? (count v))) true
    ;; Functions / atoms / volatiles / records / arbitrary objects all
    ;; fall through to the walker which knows how to handle them.
    (coll? v)    (< (estimate-bytes v) trivial-bytes-threshold)
    :else        false))

;; ---------------------------------------------------------------------------
;; Walker
;; ---------------------------------------------------------------------------

(def ^:private default-budget-bytes    262144)
(def ^:private default-branch-bytes      65536)
(def ^:private default-sample-size           8)
(def ^:private default-max-depth            32)
;; Elide a counted collection as a single branch only when both its
;; element-count *and* its estimated-bytes exceed thresholds. The
;; count gate stops a small map with one fat value (`{:big <100KB>}`)
;; from being whole-elided when descending and eliding just `:big`
;; preserves the structural information.
(def ^:private default-count-threshold      64)

(defn- non-edn-leaf?
  "Values that would print into something the EDN reader can't read
   back: functions, atoms, volatiles. Replace with their printed form
   so the wire response stays EDN-readable on the JVM side."
  [v]
  (or (fn? v)
      (instance? cljs.core/Atom v)
      (instance? cljs.core/Volatile v)))

(defn- elide
  "Build an elision marker for `v` at `path` carrying the cursor that
   resolves the original."
  [v path reason cursor sample-size]
  {:rfp.wire/elided true
   :path            path
   :reason          reason
   :cursor          cursor
   :estimate        (estimate-bytes v)
   :summary         (shallow-summary v sample-size)})

(defn safe-shape
  "Walk `v` producing an EDN-readable elided value plus a vec of
   elision metadata.

   Elision rules (in priority order):
   - Depth ≥ `max-depth` → `:too-deep` (cycle / pathological nesting).
   - Non-EDN leaves (fn / atom / volatile) → `pr-str`'d. Stringified
     result is itself size-checked and may elide.
   - Strings whose `(count v) > branch-bytes` → `:branch-too-big`.
   - Counted collections with both `(count v) > count-threshold` AND
     `(estimate-bytes v) > branch-bytes` → `:branch-too-big`. The
     count gate prevents a small map with one huge value from being
     whole-elided when descending preserves structural information.
   - Otherwise descend; at each child, check whether adding it would
     exceed `budget-bytes`. If so, elide as `:budget-exhausted`.
   - Root (depth 0) is exempt from `:budget-exhausted` so even a
     value larger than budget is descended into and as much as fits
     is shipped.

   Returns `{:value <maybe-elided> :elisions [<el> ...] :spent <int>}`."
  [v {:keys [budget-bytes branch-bytes sample-size max-depth cursor count-threshold]
      :or   {budget-bytes    default-budget-bytes
             branch-bytes    default-branch-bytes
             sample-size     default-sample-size
             max-depth       default-max-depth
             count-threshold default-count-threshold}}]
  (let [spent    (atom 0)
        elisions (atom [])
        record!  (fn [v path reason]
                   (let [el (elide v path reason cursor sample-size)]
                     (swap! elisions conj el)
                     (swap! spent + 256) ;; ~ size of the elision marker itself
                     el))
        whole-coll-too-big?
        (fn [v]
          (and (coll? v)
               (counted? v)
               (> (count v) count-threshold)
               (> (estimate-bytes v) branch-bytes)))
        walk
        (fn walk [v path depth]
          (cond
            (>= depth max-depth)
            (record! v path :too-deep)

            (non-edn-leaf? v)
            (let [s (safe-pr-str v)]
              (cond
                (> (count s) branch-bytes)
                (record! v path :branch-too-big)
                (and (pos? depth)
                     (> (+ @spent (count s)) budget-bytes))
                (record! v path :budget-exhausted)
                :else
                (do (swap! spent + (count s))
                    s)))

            (and (string? v) (> (count v) branch-bytes))
            (record! v path :branch-too-big)

            (whole-coll-too-big? v)
            (record! v path :branch-too-big)

            ;; Budget guard: at non-root, refuse to walk a value that
            ;; would push us over the total budget.
            (and (pos? depth)
                 (> (+ @spent (estimate-bytes v)) budget-bytes))
            (record! v path :budget-exhausted)

            (map? v)
            (do (swap! spent + 4)
                (reduce-kv
                 (fn [m k vv]
                   (assoc m k (walk vv (conj path k) (inc depth))))
                 {} v))

            (vector? v)
            (do (swap! spent + 4)
                (vec (map-indexed
                      (fn [i x] (walk x (conj path i) (inc depth)))
                      v)))

            (set? v)
            (do (swap! spent + 4)
                ;; Sets don't have indexable paths; we use the value
                ;; itself as the path key. Drilling into elided set
                ;; elements is unusual but consistent with get-in.
                (set (map (fn [x] (walk x (conj path x) (inc depth))) v)))

            (seq? v)
            ;; Realise lazy seqs into a vec for transport so the
            ;; result is fully concrete EDN.
            (do (swap! spent + 4)
                (vec (map-indexed
                      (fn [i x] (walk x (conj path i) (inc depth)))
                      v)))

            :else
            (do (swap! spent + (estimate-bytes v))
                v)))]
    {:value    (walk v [] 0)
     :elisions @elisions
     :spent    @spent}))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn return!
  "Wrap `v` for safe transport across the cljs-eval channel.

   Trivial values (small scalars, small collections) ship bare — no
   cursor, no walk. Non-trivial values get a cursor, are stashed in
   the session result store, and walked: oversized branches become
   elision markers carrying the path back into the stash.

   Wire shape for non-trivial values:

       {:rfp.wire/cursor   \"<session-id>/<n>\"
        :rfp.wire/value    <maybe-elided value>
        :rfp.wire/elisions [{:path [...] :type ... :count ... ...}]}

   `:rfp.wire/elisions` is omitted when the value fit entirely within
   budget; the `:value` carries elision-marker maps inline at the
   sites where data was dropped, so walking it shows the structure."
  ([v] (return! v {}))
  ([v opts]
   (if (trivial? v)
     v
     (let [cursor (alloc-cursor!)
           {:keys [value elisions]} (safe-shape v (assoc opts :cursor cursor))]
       (store! cursor v)
       (cond-> {:rfp.wire/cursor cursor
                :rfp.wire/value  value}
         (seq elisions) (assoc :rfp.wire/elisions elisions))))))

(defn raw
  "Bypass the wire wrapper: returns `v` as-is. For human REPL use
   only — bash scripts auto-wrap via ops.clj. Calls through this path
   can blow shadow-cljs's print-limit; you accept the risk."
  [v]
  v)
