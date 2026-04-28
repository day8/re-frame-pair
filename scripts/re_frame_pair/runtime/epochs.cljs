(ns re-frame-pair.runtime.epochs
  (:require [clojure.string :as str]
            [re-frame-pair.runtime.native-epoch :as native-epoch]
            [re-frame-pair.runtime.ten-x-adapter :as ten-x]))

;; The epoch-source ladder: prefer the native-epoch buffer (rf-ybv —
;; assembled epochs delivered through re-frame.trace's register-epoch-cb)
;; when it carries the requested data; fall back to 10x's match buffer
;; when re-frame predates rf-ybv or the entry has aged out of the native
;; ring. Both sources flow through the same coerce-epoch / coerce-native-
;; epoch shaping, so callers see a uniform §4.3a record regardless of
;; which buffer answered.

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
  (if-let [pub (ten-x/ten-x-public)]
    ((aget pub "latest_epoch_id"))
    (when-let [a (ten-x/ten-x-app-db-ratom)]
      (last (get-in @a [:epochs :match-ids])))))

(defn epoch-count
  "Total matches in 10x's ring buffer.

   rf1-jum: prefers the public surface's `epoch-count` (cheap —
   reads `:match-ids` length). Older 10x falls back to
   `(count (read-10x-epochs))` which goes through the legacy path."
  []
  (if-let [pub (ten-x/ten-x-public)]
    ((aget pub "epoch_count"))
    (count (ten-x/read-10x-epochs))))

(defn epoch-by-id
  "Return the coerced epoch with matching id, or nil. Prefers the
   native-epoch-buffer (upstream rf-ybv); falls back to
   `read-10x-epochs` when re-frame predates rf-ybv or the epoch has
   aged out of the native buffer."
  [id]
  (or (when-let [raw (native-epoch/find-native-epoch-by-id id)]
        (native-epoch/coerce-native-epoch raw))
      (when (or (ten-x/ten-x-loaded?)
                (not @native-epoch/native-epoch-cb-installed?))
        (->> (ten-x/read-10x-epochs)
             (some #(when (= id (ten-x/match-id %)) %))
             ten-x/coerce-epoch))))

(defn last-epoch
  "Most recently appended epoch, coerced. Prefers the native-epoch-
   buffer; falls back to 10x. Nil if neither has any epochs."
  []
  (or (some-> (native-epoch/native-epochs) last native-epoch/coerce-native-epoch)
      (when (or (ten-x/ten-x-loaded?)
                (not @native-epoch/native-epoch-cb-installed?))
        (some-> (ten-x/read-10x-epochs) last ten-x/coerce-epoch))))

(defn- ten-x-fallback-eligible?
  "True when callers should attempt the legacy 10x epoch path. If the
   native epoch callback is installed, missing 10x means native is the
   only available source and callers should return the native answer
   without leaking `read-10x-epochs`'s missing-10x exception."
  []
  (or (ten-x/ten-x-loaded?)
      (not @native-epoch/native-epoch-cb-installed?)))

(defn- native-epoch-context
  [raw-epochs]
  {:traces     (native-epoch/native-traces)
   :all-epochs raw-epochs})

(defn- coerce-native-epochs
  [raw-epochs]
  (let [ctx (native-epoch-context raw-epochs)]
    (mapv #(native-epoch/coerce-native-epoch % ctx) raw-epochs)))

(defn- raw-native-epochs-after
  [id raw-epochs]
  (cond
    (nil? id)
    raw-epochs

    (some #(= id (:id %)) raw-epochs)
    (vec (rest (drop-while #(not= id (:id %)) raw-epochs)))

    :else
    ::not-found))

(defn- raw-10x-epochs-after
  [id matches]
  (cond
    (nil? id)
    matches

    (some #(= id (ten-x/match-id %)) matches)
    (vec (rest (drop-while #(not= id (ten-x/match-id %)) matches)))

    :else
    ::not-found))

(defn- coerce-10x-epochs
  [all-matches matches]
  (let [ctx {:all-traces  (ten-x/read-10x-all-traces)
             :all-matches all-matches}]
    (mapv #(ten-x/coerce-epoch % ctx) matches)))

(defn- coerce-merged-epoch-sources
  "Coerce chronological 10x matches plus the native-buffer tail, skipping
   10x duplicates for ids the native buffer can answer more directly."
  [all-10x-matches selected-10x-matches raw-native-epochs]
  (let [native-ids (set (keep :id raw-native-epochs))
        selected-10x-matches (remove #(contains? native-ids (ten-x/match-id %))
                                     selected-10x-matches)]
    (vec (concat (coerce-10x-epochs all-10x-matches selected-10x-matches)
                 (coerce-native-epochs raw-native-epochs)))))

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
  (let [raw-native  (native-epoch/native-epochs)
        native-tail (raw-native-epochs-after id raw-native)]
    (cond
      (and (seq raw-native) (not= ::not-found native-tail))
      {:epochs (coerce-native-epochs native-tail)
       :id-aged-out? false}

      (ten-x-fallback-eligible?)
      (let [matches  (ten-x/read-10x-epochs)
            tenx-tail (raw-10x-epochs-after id matches)]
        (if (= ::not-found tenx-tail)
          {:epochs []
           :id-aged-out? true
           :requested-id id}
          {:epochs (coerce-merged-epoch-sources matches tenx-tail raw-native)
           :id-aged-out? false}))

      (not= ::not-found native-tail)
      {:epochs (coerce-native-epochs native-tail)
       :id-aged-out? false}

      :else
      {:epochs []
       :id-aged-out? true
       :requested-id id})))

(defn now-ms
  "Same clock re-frame.trace uses for trace `:start`: `performance.now()`
   when available (page-load-relative monotonic ms), else `Date.now()`
   (epoch ms). Match the trace clock — comparing across the two gives
   nonsense (perf.now is in the thousands, Date.now in the trillions).

   Public so tests redefing the clock can target this submodule's var."
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
    (if-let [raw-native (seq (native-epoch/native-epochs))]
      (let [raw-native (vec raw-native)
            ctx        (native-epoch-context raw-native)]
        (->> raw-native
             (filter (fn [raw]
                       (let [t (:start raw)]
                         (and t (>= t cutoff)))))
             (mapv #(native-epoch/coerce-native-epoch % ctx))))
      (if (ten-x-fallback-eligible?)
        (let [matches (ten-x/read-10x-epochs)]
          (->> matches
               (filter (fn [m] (let [t (:start (ten-x/find-trace m :event))]
                                 (and t (>= t cutoff)))))
               (coerce-10x-epochs matches)))
        []))))

(defn find-where
  "Walk epoch buffers in reverse chronological order and return the first
   epoch matching the predicate (a 1-arg fn taking a coerced epoch map),
   or nil if no match. The native epoch buffer is checked first; 10x is
   only consulted when it is available or when native callbacks are not.

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
  (let [raw-native (native-epoch/native-epochs)
        native-ids (set (keep :id raw-native))
        native-ctx (native-epoch-context raw-native)]
    (or (some (fn [raw]
                (let [epoch (native-epoch/coerce-native-epoch raw native-ctx)]
                  (when (pred epoch) epoch)))
              (rseq raw-native))
        (when (ten-x-fallback-eligible?)
          (let [matches (ten-x/read-10x-epochs)
                tenx-ctx {:all-traces  (ten-x/read-10x-all-traces)
                          :all-matches matches}]
            (some (fn [raw]
                    (when-not (contains? native-ids (ten-x/match-id raw))
                      (let [epoch (ten-x/coerce-epoch raw tenx-ctx)]
                        (when (pred epoch) epoch))))
                  (rseq matches)))))))

(defn find-all-where
  "Like find-where but returns every matching epoch, newest first. Use
   when you want the full trajectory of a path — 'every epoch where
   :cart changed' — not just the most recent transition."
  [pred]
  (let [raw-native (native-epoch/native-epochs)
        native-ids (set (keep :id raw-native))
        native-ctx (native-epoch-context raw-native)
        native-hits (->> (rseq raw-native)
                         (keep (fn [raw]
                                 (let [epoch (native-epoch/coerce-native-epoch raw native-ctx)]
                                   (when (pred epoch) epoch))))
                         vec)
        tenx-hits (if (ten-x-fallback-eligible?)
                    (let [matches  (ten-x/read-10x-epochs)
                          tenx-ctx {:all-traces  (ten-x/read-10x-all-traces)
                                    :all-matches matches}]
                      (->> (rseq matches)
                           (remove #(contains? native-ids (ten-x/match-id %)))
                           (keep (fn [raw]
                                   (let [epoch (ten-x/coerce-epoch raw tenx-ctx)]
                                     (when (pred epoch) epoch))))
                           vec))
                    [])]
    (vec (concat native-hits tenx-hits))))

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
