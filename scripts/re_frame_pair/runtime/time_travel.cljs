(ns re-frame-pair.runtime.time-travel
  (:require [re-frame-pair.runtime.ten-x-adapter :as ten-x]))

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
  (if-let [rf-core (ten-x/ten-x-rf-core)]
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
