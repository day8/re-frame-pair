(ns re-frame-pair.runtime.dispatch
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as rf-registrar]
            [re-frame-pair.runtime.console :as console]
            [re-frame-pair.runtime.native-epoch :as native-epoch]
            [re-frame-pair.runtime.ten-x-adapter :as ten-x]))

;; ---------------------------------------------------------------------------
;; Claude-dispatch tagging
;; ---------------------------------------------------------------------------
;; Session-local set of upstream rf-3p7 / af024c3 (commit
;; auto-generated :dispatch-ids for events we (vs. the user) fired.
;; Lets last-claude-epoch answer "the most recent epoch I dispatched"
;; without the caller threading the id back to us. Stores dispatch-ids
;; (UUIDs) rather than 10x match-ids.

(defonce claude-dispatch-ids
  ;; FIFO ring-buffer of skill-driven :dispatch-ids. Bounded so a long
  ;; debug session — `defonce` survives the runtime re-inject that
  ;; fires when discover-app's sentinel goes missing on browser
  ;; refresh — doesn't accumulate UUIDs without limit. `:entries` is a
  ;; PersistentQueue for O(overflow) head-pop eviction; `:ids` is the
  ;; matching membership set kept lock-step so `last-claude-epoch`'s
  ;; lookup stays O(1) instead of growing linearly with session age.
  ;; `:total-count` is monotonic — surfaced via app-summary so the
  ;; lifetime "skill dispatched N events" view survives eviction.
  (atom {:entries     #queue []
         :ids         #{}
         :max-size    100
         :total-count 0}))

(defn- record-claude-dispatch-id!
  "Append `dispatch-id` to the bounded claude-dispatch-ids ring buffer.
   FIFO-evicts the oldest entry when :max-size is exceeded; both
   :entries and :ids stay in sync so the read path's `contains?` over
   :ids matches the queue's current contents."
  [dispatch-id]
  (swap! claude-dispatch-ids
         (fn [{:keys [entries ids max-size total-count]}]
           (let [q (conj entries dispatch-id)]
             (loop [q  q
                    is (conj ids dispatch-id)]
               (if (> (count q) max-size)
                 (recur (pop q) (disj is (peek q)))
                 {:entries     q
                  :ids         is
                  :max-size    max-size
                  :total-count (inc total-count)}))))))

(defn- traces-atom
  "JS-interop accessor for the `re-frame.trace/traces` atom (re-frame's
   internal trace ring). Routed through the same `goog.global` probe
   as the other re-frame.trace surfaces so the lookup degrades to nil
   if a future re-frame trace-ns refactor removes or relocates it."
  []
  (when-let [g (some-> js/goog .-global)]
    (ten-x/aget-path g ["re_frame" "trace" "traces"])))

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
   :dispatch-id tag generated), or when the trace atom isn't
   reachable on the loaded re-frame build."
  []
  (when-let [traces-ref (traces-atom)]
    (let [traces @traces-ref]
      (->> (if (vector? traces) (rseq traces) (reverse traces))
           (some (fn [t] (when (= :event (:op-type t))
                           (-> t :tags :dispatch-id))))))))

(defn tagged-dispatch!
  "Dispatch an event (queued) — the handler runs out of band, so the
   :dispatch-id is generated only when handle eventually fires.

   `current-who` is set to `:claude` for the duration of the enqueue
   call; the handler itself runs out of band, so handler-side console
   output tags `:app`. Use `tagged-dispatch-sync!` when you need
   handler output tagged.

   Returns:
     {:ok? true :queued? true :event ev :dispatch-id nil :epoch-id nil
      :note <string>}

   `:dispatch-id` and `:epoch-id` are structurally nil on the queued
   path — the handler runs out of band, so neither id is available at
   enqueue time, regardless of re-frame version. The `:note` makes the
   structural nil explicit so callers don't conflate it with the
   tagged-dispatch-sync! 'predates rf-3p7' nil. Use tagged-dispatch-sync!
   if you need :dispatch-id correlation back to an epoch."
  [event-v]
  (reset! console/current-who :claude)
  (try
    (rf/dispatch event-v)
    (finally
      (reset! console/current-who :app)))
  {:ok? true
   :queued? true
   :event event-v
   :epoch-id nil
   :dispatch-id nil
   :note "Queued dispatch — :dispatch-id and :epoch-id are structurally nil at enqueue time (handler runs out of band). Use tagged-dispatch-sync! if you need :dispatch-id correlation."})

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
   ~50ms cb-delivery debounce flushes. Call `collect-after-dispatch`
   with the returned `:dispatch-id` after a bash-side wait past the
   trace-debounce.

   Handler errors: re-frame's default error handler logs to console
   and re-throws, which would propagate through `cljs-eval` as an
   nREPL `:err` and break the bb shim's edn parsing. We catch and
   return a structured `:reason :handler-threw` instead.

   Returns:
     {:ok? true :event ev :dispatch-id <uuid|nil> :epoch-id nil}
     {:ok? false :reason :handler-threw :error ... :error-data ...}"
  [event-v]
  (reset! console/current-who :claude)
  (try
    (try
      (rf/dispatch-sync event-v)
      (let [dispatch-id (recent-dispatch-id)]
        (when dispatch-id
          (record-claude-dispatch-id! dispatch-id))
        {:ok?         true
         :event       event-v
         :dispatch-id dispatch-id
         :epoch-id    nil
         :note        (if dispatch-id
                        "10x's epoch lands after the trace-debounce (~50ms); resolve via collect-after-dispatch with :dispatch-id."
                        "re-frame predates rf-3p7 (commit af024c3) — :dispatch-id auto-generation not available; correlation by :dispatch-id won't work.")})
      (catch :default e
        (let [stack (try (.-stack e) (catch :default _ ""))]
          (console/append-console-entry!
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
    (finally
      (reset! console/current-who :app))))

(defn last-claude-epoch
  "Most recent epoch dispatched by the skill in this session. Resolves
   by walking the native-epoch-buffer first (newest-first) for the
   first epoch whose `:dispatch-id` appears in `claude-dispatch-ids`,
   then falls back to 10x's buffer when 10x is loaded (covers older
   re-frame, or a dispatch-id that has aged out of the native ring).
   Returns nil — never throws — when neither source can answer."
  []
  (let [ours        (:ids @claude-dispatch-ids)
        from-native (some->> (native-epoch/native-epochs)
                             reverse
                             (some (fn [raw]
                                     (when (contains? ours (:dispatch-id raw))
                                       raw)))
                             native-epoch/coerce-native-epoch)]
    (or from-native
        (when (ten-x/ten-x-loaded?)
          (some->> (ten-x/read-10x-epochs)
                   reverse
                   (some (fn [raw]
                           (let [evt-trace   (ten-x/find-trace raw :event)
                                 dispatch-id (-> evt-trace :tags :dispatch-id)]
                             (when (contains? ours dispatch-id) raw))))
                   ten-x/coerce-epoch)))))

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
          (when (= dispatch-id (-> (ten-x/find-trace m :event) :tags :dispatch-id))
            m))
        (reverse matches)))

(defn chained-dispatch-ids
  "Vec of dispatch-ids whose event-trace transitively descends from
   `parent-id` via `:parent-dispatch-id` — direct children, grandchildren,
   and deeper, fired via `:fx [:dispatch ...]` cascades from within the
   parent's handler (or one of its descendants'). Walks `matches` in
   chronological order so the returned vec preserves dispatch order.

   Mirrors the fixed-point closure in `collect-cascade-from-buffer` (the
   rf-4mr / native-buffer path) so the legacy --trace fallback reports
   the same cascade depth as the modern dispatch-and-settle! path."
  [parent-id matches]
  (let [pairs (->> matches
                   (keep (fn [m]
                           (let [tags (:tags (ten-x/find-trace m :event))
                                 id   (:dispatch-id tags)
                                 pid  (:parent-dispatch-id tags)]
                             (when id [id pid]))))
                   vec)
        ids   (loop [acc #{parent-id}]
                (let [grown (into acc
                                  (keep (fn [[id pid]]
                                          (when (and pid (contains? acc pid))
                                            id)))
                                  pairs)]
                  (if (= grown acc) acc (recur grown))))]
    (->> pairs
         (keep (fn [[id _pid]]
                 (when (and (not= id parent-id) (contains? ids id))
                   id)))
         vec)))

(defn collect-after-dispatch
  "Companion to `tagged-dispatch-sync!`: after a bash-side wait past
   the trace-debounce, resolve the epoch by `:dispatch-id` correlation
   (rf-3p7 / af024c3 in re-frame core) and return its coerced form
   plus any chained children fired via `:fx [:dispatch ...]`.

   The bash shim drives the wait — `dispatch-and-collect` (the cljs-only
   Promise variant below) doesn't survive the cljs-eval round-trip back
   to babashka.

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
    (let [matches (ten-x/read-10x-epochs)
          ours-m  (find-epoch-by-dispatch-id dispatch-id matches)]
      (if ours-m
        (let [chain-ids (chained-dispatch-ids dispatch-id matches)]
          (cond-> {:ok?         true
                   :dispatch-id dispatch-id
                   :epoch-id    (ten-x/match-id ours-m)
                   :epoch       (ten-x/coerce-epoch ours-m)}
            (seq chain-ids) (assoc :chained-dispatch-ids chain-ids)))
        {:ok?         false
         :reason      :no-new-epoch
         :dispatch-id dispatch-id
         :hint        "10x has no match carrying this :dispatch-id. trace-enabled? may be false, the handler may have thrown before tracing finished, or the tab may be throttled."}))))

;; ---------------------------------------------------------------------------
;; CLJS-only API — Promise-returning helpers
;; ---------------------------------------------------------------------------
;; The fns below are callable from CLJS only. Their JS Promise return
;; values can't round-trip through cljs-eval back to babashka.

(defn dispatch-and-collect
  "dispatch-sync the event, wait for the trace debounce + a render
   frame so renders land in 10x's match-info, then resolve the epoch
   produced via :dispatch-id correlation.

   CLJS-only — the JS Promise return doesn't survive cljs-eval. Bash
   callers use `tagged-dispatch-sync!` + `collect-after-dispatch`
   instead, or `dispatch-and-settle!` for the cascaded variant.

   Returns a JS Promise."
  [event-v]
  (js/Promise.
   (fn [resolve _reject]
     (let [{:keys [dispatch-id]} (tagged-dispatch-sync! event-v)
           settle (fn settle []
                    (js/requestAnimationFrame
                     (fn []
                       (let [matches (ten-x/read-10x-epochs)
                             ours-m  (when dispatch-id
                                       (find-epoch-by-dispatch-id dispatch-id matches))]
                         (if ours-m
                           (resolve (clj->js
                                     {:ok?         true
                                      :dispatch-id dispatch-id
                                      :epoch-id    (ten-x/match-id ours-m)
                                      :epoch       (ten-x/coerce-epoch ours-m)}))
                           (resolve (clj->js
                                     {:ok?         false
                                      :reason      :no-new-epoch
                                      :event       event-v
                                      :dispatch-id dispatch-id
                                      :hint        "10x did not append a match for this :dispatch-id within the debounce + 1 frame."})))))))]
       (js/setTimeout settle trace-debounce-settle-ms)))))

(declare build-stub-overrides validate-fx-ids)

;; ---------------------------------------------------------------------------
;; dispatch-and-settle! — bridge for the bash shim
;; ---------------------------------------------------------------------------
;; re-frame core's `dispatch-and-settle` (rf-4mr) returns a Promise
;; that resolves once the cascade settles. The Promise can't
;; round-trip through cljs-eval, so dispatch-and-settle! stores the
;; eventual resolution in settle-pending keyed by an opaque handle;
;; the bash shim polls await-settle to read the settled record.

(defn dispatch-and-settle-fn
  "JS-interop accessor for `re-frame.core/dispatch-and-settle` (rf-4mr,
   commit f8f0f59). Returns nil when re-frame predates rf-4mr."
  []
  (when-let [g (some-> js/goog .-global)]
    (ten-x/aget-path g ["re_frame" "core" "dispatch_and_settle"])))

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
  ([root-id] (collect-cascade-from-buffer root-id (native-epoch/native-epochs)))
  ([root-id epochs]
   (when root-id
     (let [ids (loop [acc #{root-id}]
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
                                  epochs)
           ctx           {:traces (native-epoch/native-traces) :all-epochs epochs}]
       {:root-epoch         (some-> root-raw (native-epoch/coerce-native-epoch ctx))
        :cascaded-epoch-ids (mapv :dispatch-id cascaded-raws)
        :cascaded-epochs    (mapv #(native-epoch/coerce-native-epoch % ctx) cascaded-raws)}))))

(defonce settle-pending
  ;; handle-uuid -> {:settled? bool ... result fields}.
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
   `dispatch-sync` that runs inside `dispatch-and-settle`. The root
   :dispatch-id is captured immediately after dispatch-sync via
   `recent-dispatch-id` and accumulated into `claude-dispatch-ids`.

   The settled record is reconstituted from the native-epoch-buffer
   rather than the Promise's clj->js'd value (re-frame's resolve!
   stringifies keywords; the native buffer keeps them intact).

   `opts` forwards to re-frame.core/dispatch-and-settle. Defaults:
   :timeout-ms 5000, :settle-window-ms 100, :include-cascaded? true.
   Two extra opts (consumed here, stripped before forwarding):
     :stub-fx-ids — vec of fx-id keywords; record-only stubs swap in.
     :overrides   — explicit `{fx-id stub-fn}` map (rf-ge8). Wins
                    over `:stub-fx-ids` if both supplied.

   Returns synchronously:
     {:ok? true :handle <uuid> :event ev :dispatch-id <id> :pending? true
      :stubbed-fx-ids [...]?}
     {:ok? false :reason :dispatch-and-settle-unavailable :hint ...}
     {:ok? false :reason :handler-threw :error ... :event ev}"
  ([event-v] (dispatch-and-settle! event-v {}))
  ([event-v opts]
   (let [stub-ids   (:stub-fx-ids opts)
         validation (when (seq stub-ids) (validate-fx-ids stub-ids))]
     (cond
       (and validation (not (:ok? validation)))
       validation

       (nil? (dispatch-and-settle-fn))
       {:ok?    false
        :reason :dispatch-and-settle-unavailable
        :hint   "re-frame predates rf-4mr (commit f8f0f59) — fall back to tagged-dispatch-sync! + collect-after-dispatch."}

       :else
       (let [d-and-s     (dispatch-and-settle-fn)
             handle      (str (random-uuid))
             overrides   (or (:overrides opts)
                             (when-let [ids (seq stub-ids)]
                               (build-stub-overrides ids)))
             settle-opts (dissoc opts :overrides :stub-fx-ids)
             event-meta  (cond-> event-v
                           overrides (vary-meta assoc :re-frame/fx-overrides overrides))]
         (reset! console/current-who :claude)
         (try
           (let [p                (d-and-s event-meta settle-opts)
                 root-dispatch-id (recent-dispatch-id)]
             (when root-dispatch-id
               (record-claude-dispatch-id! root-dispatch-id))
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
               (console/append-console-entry!
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
             (reset! console/current-who :app))))))))

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
     - unknown handle:   {:settled? false :ok? false :reason :unknown-handle :handle h}

   `:ok?` is omitted on the still-pending shape because pending isn't
   an error. The unknown-handle shape carries `:ok? false` so callers
   that gate on `:ok?` correctly classify it as a failure.

   On a settled response, the handle is removed from the atom — pollers
   should not call await-settle on the same handle twice."
  [handle]
  (if-let [entry (get @settle-pending handle)]
    (if (:settled? entry)
      (do (swap! settle-pending dissoc handle)
          entry)
      {:settled? false :pending? true :handle handle})
    {:settled? false :ok? false :reason :unknown-handle :handle handle}))

;; ---------------------------------------------------------------------------
;; dispatch-with bridge — fx-overrides for safe iteration
;; ---------------------------------------------------------------------------
;; re-frame core's `dispatch-with` (rf-ge8) tags an event with
;; `:re-frame/fx-overrides` meta; do-fx-after binds *current-overrides*
;; for that event's fx execution and propagates the meta to children
;; queued via `:fx [:dispatch ...]`.

(defn dispatch-with-fn
  "JS-interop accessor for `re-frame.core/dispatch-with` (rf-ge8,
   commit 2651a30). Returns nil when re-frame predates rf-ge8."
  []
  (when-let [g (some-> js/goog .-global)]
    (ten-x/aget-path g ["re_frame" "core" "dispatch_with"])))

(defn dispatch-sync-with-fn
  "JS-interop accessor for `re-frame.core/dispatch-sync-with` (rf-ge8)."
  []
  (when-let [g (some-> js/goog .-global)]
    (ten-x/aget-path g ["re_frame" "core" "dispatch_sync_with"])))

(defonce stub-effect-log
  ;; FIFO ring-buffer of stubbed-effect invocations.
  (atom {:entries [] :max-size 200}))

(defn record-only-stub
  "Build a record-only stub for `fx-id`: a 1-arg fn that captures its
   value into `stub-effect-log` and returns nil. The original fx's
   side-effect (HTTP, navigation, etc.) is suppressed."
  [fx-id]
  (fn [value]
    (swap! stub-effect-log
           (fn [{:keys [entries max-size]}]
             {:entries  (vec (take-last max-size
                                        (conj entries
                                              {:fx-id fx-id
                                               :value value
                                               :ts    (js/Date.now)
                                               :who   @console/current-who})))
              :max-size max-size}))
    nil))

(defn build-stub-overrides
  "Convert a vec of fx-id keywords into a `{fx-id record-only-stub}`
   map suitable for `dispatch-with`. Public for tests."
  [fx-ids]
  (into {} (for [k fx-ids] [k (record-only-stub k)])))

(defn validate-fx-ids
  "Verify every fx-id keyword in `fx-ids` names a registered :fx
   handler. Returns `{:ok? true}` when every id resolves; otherwise
   a structured error listing the bad ids alongside the full request."
  [fx-ids]
  (let [unstubbable (filterv #{:db} fx-ids)
        unknown     (filterv #(nil? (rf-registrar/get-handler :fx %)) fx-ids)]
    (cond
      (seq unstubbable)
      {:ok?         false
       :reason      :unstubbable-fx
       :unstubbable unstubbable
       :requested   (vec fx-ids)
       :hint        ":db is re-frame's app-db effect. Stubbing it would suppress state updates for the probed dispatch."}

      (seq unknown)
      {:ok?       false
       :reason    :unregistered-fx
       :unknown   unknown
       :requested (vec fx-ids)
       :hint      "Unknown fx-id(s) — pass an id registered with reg-fx. Inspect available ids with `(re-frame-pair.runtime/registrar-list :fx)`."}
      :else
      {:ok? true})))

(defn stubbed-effects-since
  "Slice of `stub-effect-log` with `:ts >= since-ts`. Returns
   `{:ok? true :entries [...] :now <ms>}`."
  ([] (stubbed-effects-since 0))
  ([since-ts]
   {:ok?     true
    :entries (vec (filter #(>= (:ts %) since-ts) (:entries @stub-effect-log)))
    :now     (js/Date.now)}))

(defn clear-stubbed-effects!
  "Reset the stub-effect-log entries to empty (preserves :max-size)."
  []
  (swap! stub-effect-log assoc :entries [])
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
      (reset! console/current-who :claude)
      (try
        (d-with event-v overrides)
        {:ok?            true
         :queued?        true
         :event          event-v
         :stubbed-fx-ids (vec (sort (keys overrides)))}
        (finally (reset! console/current-who :app))))
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
      (reset! console/current-who :claude)
      (try
        (try
          (d-sync-with event-v overrides)
          (let [dispatch-id (recent-dispatch-id)]
            (when dispatch-id (record-claude-dispatch-id! dispatch-id))
            {:ok?            true
             :event          event-v
             :dispatch-id    dispatch-id
             :epoch-id       nil
             :stubbed-fx-ids (vec (sort (keys overrides)))})
          (catch :default e
            (let [stack (try (.-stack e) (catch :default _ ""))]
              (console/append-console-entry!
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
        (finally (reset! console/current-who :app))))
    {:ok?    false
     :reason :dispatch-sync-with-unavailable
     :hint   "re-frame predates rf-ge8 (commit 2651a30) — upgrade or use a global stub."}))

(defn dispatch-with-stubs!
  "Convenience: `dispatch-with!` with record-only stubs for each fx-id
   in `fx-ids`. The bash shim's `--stub <fx-id>` flag drives this."
  [event-v fx-ids]
  (let [v (validate-fx-ids fx-ids)]
    (if (:ok? v)
      (dispatch-with! event-v (build-stub-overrides fx-ids))
      v)))

(defn dispatch-sync-with-stubs!
  "`dispatch-sync-with!` counterpart of `dispatch-with-stubs!`. Same
   `validate-fx-ids` short-circuit on unregistered fx-ids."
  [event-v fx-ids]
  (let [v (validate-fx-ids fx-ids)]
    (if (:ok? v)
      (dispatch-sync-with! event-v (build-stub-overrides fx-ids))
      v)))
