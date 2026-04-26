(ns app.events
  (:require [re-frame.core :as rf]
            [app.db        :as db]))

;; -----------------------------------------------------------------------------
;; Bootstrap
;; -----------------------------------------------------------------------------

(rf/reg-event-db
 :initialize
 (fn [_ _]
   db/initial-db))

;; -----------------------------------------------------------------------------
;; Counter — three reg-event-db handlers. The "experiment loop" recipe
;; in SKILL.md uses these because they're the simplest possible state
;; mutation: dispatch, watch app-db, observe.
;; -----------------------------------------------------------------------------

(rf/reg-event-db
 :counter/inc
 (fn [db _]
   (-> db
       (update :counter inc)
       (update :events-fired inc))))

(rf/reg-event-db
 :counter/dec
 (fn [db _]
   (-> db
       (update :counter dec)
       (update :events-fired inc))))

(rf/reg-event-db
 :counter/reset
 (fn [db _]
   (-> db
       (assoc :counter 0)
       (update :events-fired inc))))

;; -----------------------------------------------------------------------------
;; Items — a reg-event-db with a non-trivial path-based update.
;; -----------------------------------------------------------------------------

(rf/reg-event-db
 :item/inc-qty
 (fn [db [_ id]]
   (-> db
       (update :items
               (fn [items]
                 (mapv (fn [item]
                         (if (= id (:id item))
                           (update item :qty inc)
                           item))
                       items)))
       (update :events-fired inc))))

;; -----------------------------------------------------------------------------
;; Coupon — a reg-event-fx with a follow-up :dispatch effect. This
;; exercises the :effects/fired flattening in coerce-epoch.
;; -----------------------------------------------------------------------------

(rf/reg-event-fx
 :coupon/apply
 (fn [{:keys [db]} [_ code]]
   {:db       (-> db
                  (assoc :coupon {:code code :status (if (seq code) :applied :none)})
                  (update :events-fired inc))
    :fx       [(when (seq code)
                 [:dispatch [:analytics/track :coupon-applied {:code code}]])]}))

(rf/reg-event-db
 :analytics/track
 (fn [db [_ _kind _payload]]
   ;; In a real app this would emit a side-effect (xhrio, gtag). Here
   ;; we just bump the counter so the dispatch shows up in the trace.
   (update db :events-fired inc)))

;; -----------------------------------------------------------------------------
;; Deliberately broken handlers — for the "experiment loop" recipe to
;; iterate against. They are real bugs the agent should observe and fix.
;; -----------------------------------------------------------------------------

(rf/reg-event-db
 :broken/throw
 (fn [_db _event]
   ;; Intentional: re-frame catches this and reports via re-frame.loggers.
   (throw (ex-info "broken-handler-on-purpose"
                   {:hint "This handler always throws. The agent should observe the error in 10x and propose a fix."}))))

(rf/reg-event-db
 :broken/non-map
 (fn [_db _event]
   ;; Intentional: returning a non-map sets app-db to a vector. Most
   ;; subs will then break on next tick. Reset event clears it.
   [:not :a :map]))
