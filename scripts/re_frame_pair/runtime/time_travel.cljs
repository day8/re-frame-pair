(ns re-frame-pair.runtime.time-travel
  (:require [re-frame-pair.runtime.ten-x-adapter :as ten-x]))

;; 10x's epoch navigation has TWO surfaces:
;;
;; 1. `day8.re-frame-10x.public` (rf1-jum) — stable public namespace.
;;    Exports event-identifier string constants (`previous-epoch`,
;;    `next-epoch`, `most-recent-epoch`, `load-epoch`, `replay-epoch`,
;;    `reset-epochs`, `reset-app-db-event`) plus a `dispatch!` bridge
;;    fn into 10x's inlined router. The strings are the durable
;;    contract; their internal kw counterparts can rename.
;;
;; 2. `day8.re-frame-10x.navigation.epochs.events` — internal kws
;;    (`::previous` / `::next` / `::most-recent` / `::load` / `::replay`)
;;    dispatched directly via the inlined `re-frame.core/dispatch`. The
;;    fallback path for 10x JARs that predate the public surface.
;;
;; Each navigation event also dispatches `::reset-current-epoch-app-db`,
;; which resets the *userland* re-frame.db/app-db to that epoch's
;; pre-event state — but ONLY when `[:settings :app-db-follows-events?]`
;; is true. That's the actual time-travel mechanism. The setting defaults
;; to true (loaded from local-storage with :or true); `undo-status`
;; reports its current value so callers can detect the off case.

(def ^:private ten-x-events-ns
  "Fully-qualified namespace prefix for 10x's internal epoch-navigation
   events — the fallback path when the public surface is missing."
  "day8.re-frame-10x.navigation.epochs.events")

(def ^:private ten-x-settings-ns
  "Fully-qualified namespace prefix for 10x's settings events."
  "day8.re-frame-10x.panels.settings.events")

(defn- ten-x-event-kw [evt-ns local]
  (keyword evt-ns (name local)))

(defn- public-event-id
  "Return the public event-id string for `export-name` (the JS-munged
   form, e.g. \"previous_epoch\") if the 10x public surface is loaded
   AND exposes that constant. Nil otherwise — caller should fall back
   to the internal-kw path."
  [export-name]
  (when-let [pub (ten-x/ten-x-public)]
    (let [v (aget pub export-name)]
      (when (string? v) v))))

(defn- public-dispatch-fn
  "Return the public `dispatch!` JS fn if loaded, else nil. Cached on
   the call site to keep the goog-global walk off the hot path."
  []
  (when-let [pub (ten-x/ten-x-public)]
    (let [d (aget pub "dispatch_BANG_")]
      (when (fn? d) d))))

(defn- ten-x-dispatch!
  "Dispatch `event-v` into 10x's inlined re-frame instance. Prefers the
   public `dispatch!` bridge (rf1-jum) when loaded; falls back to a
   direct call into the inlined `re-frame.core/dispatch` for older 10x
   builds that predate the public surface."
  [event-v]
  (cond
    (public-dispatch-fn)
    (let [d (public-dispatch-fn)]
      (try
        (d (clj->js event-v))
        {:ok? true :event event-v}
        (catch :default e
          {:ok? false :reason :dispatch-threw
           :event event-v :message (.-message e)})))

    (ten-x/ten-x-rf-core)
    (let [dispatch-fn (aget (ten-x/ten-x-rf-core) "dispatch")]
      (try
        (dispatch-fn event-v)
        {:ok? true :event event-v}
        (catch :default e
          {:ok? false :reason :dispatch-threw
           :event event-v :message (.-message e)})))

    :else
    {:ok? false :reason :ten-x-missing}))

(defn- nav-event
  "Build the dispatch head for a navigation op. `public-export` is the
   munged-JS name of the public event-id constant; `internal-local`
   is the unqualified internal kw name. Returns the public string if
   the surface exposes it, else the internal kw."
  [public-export internal-local]
  (or (public-event-id public-export)
      (ten-x-event-kw ten-x-events-ns internal-local)))

(defn- ten-x-state
  "Read 10x's epoch + settings state from its inlined app-db. Returns
   nil if 10x is not loaded."
  []
  (when-let [a (ten-x/ten-x-app-db-ratom)]
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
  (let [r (ten-x-dispatch! [(nav-event "previous_epoch" "previous")])]
    (cond-> r
      (:ok? r) (merge (or (check-follows-events) {})))))

(defn undo-step-forward
  "Move 10x's cursor one epoch forward."
  []
  (let [r (ten-x-dispatch! [(nav-event "next_epoch" "next")])]
    (cond-> r
      (:ok? r) (merge (or (check-follows-events) {})))))

(defn undo-to-epoch
  "Jump 10x's cursor to a specific epoch id (must exist in the buffer)."
  [id]
  (let [present? (when-let [s (ten-x-state)] (some #(= id %) (:match-ids s)))]
    (if-not present?
      {:ok? false :reason :unknown-epoch-id :id id
       :hint "epoch id not in 10x's current buffer; use undo-status to see :match-ids"}
      (let [r (ten-x-dispatch! [(nav-event "load_epoch" "load") id])]
        (cond-> r
          (:ok? r) (merge (or (check-follows-events) {})))))))

(defn undo-most-recent
  "Jump 10x's cursor to the most recent epoch (cancels any time-travel)."
  []
  (let [r (ten-x-dispatch! [(nav-event "most_recent_epoch" "most-recent")])]
    (cond-> r
      (:ok? r) (merge (or (check-follows-events) {})))))

(defn undo-replay
  "Re-dispatch the currently selected epoch's event. 10x first resets
   userland app-db to the epoch's pre-event state, waits for reagent
   quiescence, then re-fires the event."
  []
  (ten-x-dispatch! [(nav-event "replay_epoch" "replay")]))
