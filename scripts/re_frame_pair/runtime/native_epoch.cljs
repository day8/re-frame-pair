(ns re-frame-pair.runtime.native-epoch
  (:require [re-frame-pair.runtime.ten-x-adapter :as ten-x]))

;; re-frame core's `register-epoch-cb` (commit 4a53afb) ships assembled
;; epoch records once per `:event` trace. We drain into a session-local
;; ring buffer and a sibling trace ring buffer (the latter feeds renders
;; / sub-runs correlation by id range — they aren't attached to the
;; native epoch by `:child-of` because they fire outside the synchronous
;; handler frame).
;;
;; Feature detection for every re-frame.trace surface this file consumes
;; is via JS interop on `goog.global` rather than direct namespace
;; references. The symbol may not exist on the re-frame the file is
;; compiled against — `register-epoch-cb` for instance is missing on
;; re-frame 1.4.5 (the runtime-test build's pinned dep), so a direct
;; `(re-frame.trace/register-epoch-cb ...)` would fail to compile.
;; `register-trace-cb`, the `traces` atom, and the `trace-enabled?`
;; goog-define have all been stable since re-frame's earliest trace
;; releases, but they're routed through the same probe so any future
;; re-frame trace-ns rearrangement (rename, split, feature-flagged
;; removal) degrades gracefully — the probe returns nil instead of
;; breaking compile.

(defonce native-epoch-buffer
  (atom {:entries [] :max-size 50}))

(defonce native-trace-buffer
  (atom {:entries #queue [] :max-size 5000}))

(defonce native-epoch-cb-installed? (atom false))
(defonce native-trace-cb-installed? (atom false))

(defn receive-native-epochs!
  "register-epoch-cb callback. Appends each delivered epoch to the
   ring buffer, keeping the most recent :max-size entries."
  [epochs]
  (swap! native-epoch-buffer
         (fn [{:keys [entries max-size]}]
           {:entries  (vec (take-last max-size (into entries epochs)))
            :max-size max-size})))

(defn receive-native-traces!
  "register-trace-cb callback. Appends raw traces from each delivery
   batch to the trace ring buffer.

   `:entries` is a `PersistentQueue` rather than a vector so the FIFO
   trim is O(overflow) pop-from-head, not the O(max-size) vec rebuild
   the prior `(vec (take-last max-size ...))` shape did every batch.
   The cb fires on every re-frame.trace flush (RAF tick + handler
   boundaries), so a 5000-element rebuild per call dominated the
   runtime's in-browser hot path."
  [batch]
  (swap! native-trace-buffer
         (fn [{:keys [entries max-size]}]
           {:entries  (loop [q (into entries batch)]
                        (if (> (count q) max-size)
                          (recur (pop q))
                          q))
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
    (ten-x/aget-path g ["re_frame" "trace" "register_epoch_cb"])))

(defn register-trace-cb-fn
  "JS-interop accessor for `re-frame.trace/register-trace-cb`. The
   symbol has been part of re-frame since 2017, but routing the lookup
   through the same `goog.global` probe as `register-epoch-cb-fn`
   keeps the file resilient to any future re-frame trace-ns
   rearrangement."
  []
  (when-let [g (some-> js/goog .-global)]
    (ten-x/aget-path g ["re_frame" "trace" "register_trace_cb"])))

(defn install-native-epoch-cb!
  "Register a `::re-frame-pair` epoch callback if re-frame core ships
   `register-epoch-cb`. Idempotent. Silent no-op on older re-frame —
   legacy callers fall back through `read-10x-epochs`."
  []
  (when-not @native-epoch-cb-installed?
    (when-let [register-fn (register-epoch-cb-fn)]
      (reset! native-epoch-cb-installed? true)
      ;; Keyword namespace pinned to `re-frame-pair.runtime` (not the
      ;; auto-resolved `::` form) so the registration id stays stable
      ;; after the native-epoch extraction.
      (register-fn :re-frame-pair.runtime/re-frame-pair receive-native-epochs!))))

(defn install-native-trace-cb!
  "Register a `::re-frame-pair-traces` trace callback to feed the
   trace ring buffer. Idempotent. Costs one closure invocation per
   debounce tick, regardless of trace volume. Silent no-op if a
   future re-frame removes `register-trace-cb` from the trace ns."
  []
  (when-not @native-trace-cb-installed?
    (when-let [register-fn (register-trace-cb-fn)]
      (reset! native-trace-cb-installed? true)
      ;; Keyword namespace pinned to `re-frame-pair.runtime` (not the
      ;; auto-resolved `::` form) so the registration id stays stable
      ;; after the native-epoch extraction.
      (register-fn :re-frame-pair.runtime/re-frame-pair-traces receive-native-traces!))))

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

(defn find-native-epoch-by-id
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

(defn- subscription-trace?
  [trace]
  (contains? #{:sub/create :sub/run :sub/dispose} (:op-type trace)))

(defn- sub-trace-key
  "Stable key for a subscription trace. Live re-frame tags `:sub/run`
   with the reaction id after the reaction has been constructed; older
   or synthetic traces may not, so query-v is the fallback."
  [trace]
  (let [tags (:tags trace)]
    (or (:reaction tags)
        (:query-v tags)
        (:query tags))))

(defn- reset-sub-reaction-state
  [state]
  (into {}
        (comp
         (filter (fn [[_ sub]] (not (:disposed? sub))))
         (map (fn [[k sub]]
                [k (dissoc sub
                           :order :created? :run? :disposed?
                           :previous-value :sub/traits)])))
        state))

(defn- apply-sub-trace
  [state trace]
  (let [tags (:tags trace)
        k    (sub-trace-key trace)
        q    (or (:query-v tags) (:query tags))]
    (if (and k q)
      (let [state (-> state
                      (update-in [k :order] (fnil conj []) (:op-type trace))
                      (assoc-in [k :subscription] q))]
        (case (:op-type trace)
          :sub/create
          (cond-> state
            (false? (:cached? tags)) (assoc-in [k :created?] true))

          :sub/run
          (update state k
                  (fn [sub]
                    (let [sub (or sub {})
                          sub (if (contains? tags :value)
                                (cond-> sub
                                  (contains? sub :value)
                                  (assoc :previous-value (:value sub))

                                  true
                                  (assoc :value (:value tags)))
                                sub)]
                      (assoc sub :run? true))))

          :sub/dispose
          (assoc-in state [k :disposed?] true)

          state))
      state)))

(defn- mark-unchanged-sub-runs
  [state]
  (reduce-kv
   (fn [m k sub]
     (if (and (contains? sub :previous-value)
              (contains? sub :value)
              (= (:previous-value sub) (:value sub)))
       (assoc-in m [k :sub/traits :unchanged?] true)
       m))
   state
   state))

(defn- process-sub-traces
  [state traces]
  (-> (reduce apply-sub-trace state traces)
      mark-unchanged-sub-runs))

(defn- native-sub-state
  "Reconstruct the legacy 10x `:sub-state` shape for a native epoch.
   Native `register-epoch-cb` epochs do not carry render-time sub runs,
   so we rebuild the same reaction-state projection from the trace ring:
   previous runs before the epoch establish prior values, then in-epoch
   runs set `:sub/traits :unchanged?` when the final value did not
   change."
  [traces first-id last-id]
  (let [sub-traces   (filterv subscription-trace? traces)
        before       (filterv (fn [t]
                                (let [id (:id t)]
                                  (and id (< id first-id))))
                              sub-traces)
        in-epoch     (filterv (fn [t]
                                (let [id (:id t)]
                                  (and id (<= first-id id last-id))))
                              sub-traces)
        before-state (process-sub-traces {} before)]
    {:reaction-state (process-sub-traces
                      (reset-sub-reaction-state before-state)
                      in-epoch)}))

(defn- native-epoch->match
  "Normalise a native `register-epoch-cb` epoch to the legacy match shape
   consumed by `coerce-epoch`. This keeps native and 10x fallback epochs
   on one semantic path for `:subs/ran`, `:subs/cache-hit`, renders,
   source metadata, and effect flattening."
  [raw all-epochs traces]
  (let [in-range      (traces-in-native-epoch-range raw all-epochs traces)
        trace-ids     (keep :id (concat in-range
                                        [(:event-handler raw)
                                         (:event-do-fx raw)]))
        first-id      (:id raw)
        last-id       (reduce max first-id trace-ids)
        event-trace   {:id       first-id
                       :op-type  :event
                       :start    (:start raw)
                       :duration (:duration raw)
                       :tags     {:event              (:event raw)
                                  :event/original     (get raw :event/original (:event raw))
                                  :app-db-before      (:app-db/before raw)
                                  :app-db-after       (:app-db/after raw)
                                  :coeffects          (:coeffects raw)
                                  :effects            (:effects raw)
                                  :interceptors       (:interceptors raw)
                                  :dispatch-id        (:dispatch-id raw)
                                  :parent-dispatch-id (:parent-dispatch-id raw)}}
        handler-trace (some-> (:event-handler raw)
                              (assoc :op-type :event/handler))
        do-fx-trace   (some-> (:event-do-fx raw)
                              (assoc :op-type :event/do-fx))
        close-trace   {:id last-id :op-type :reagent/quiescent}]
    {:id         first-id
     :match-info (vec (remove nil? [event-trace
                                    handler-trace
                                    do-fx-trace
                                    close-trace]))
     :sub-state  (native-sub-state traces first-id last-id)
     :timing     {:re-frame/event-time (:duration raw)}}))

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

   The native epoch is first normalised to the legacy match shape and
   then passed through `coerce-epoch`; `:subs/ran`, `:subs/cache-hit`,
   and `:renders` therefore share the same semantics as the 10x
   fallback path.

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
     (let [match (native-epoch->match raw all-epochs traces)]
       (ten-x/coerce-epoch match {:all-traces  traces
                                  :all-matches [match]})))))
