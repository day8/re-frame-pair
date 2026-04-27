(ns app.events
  (:require [re-frame.core         :as rf]
            [day8.re-frame.tracing :refer-macros [fn-traced]]
            ;; rfp-rsg — opt in to re-frame-pair's registration macro
            ;; so handler-source can return {:file :line} for handlers
            ;; defined here, working around re-frame's interceptor
            ;; wrapper hiding (meta f). See docs/handler-source-meta.md.
            [re-frame-pair.runtime :as rfpr :refer-macros [reg-event-db]]
            [app.db                :as db]))

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

;; rfp-mkf — wrapped with fn-traced so each sub-form (the threading
;; pipeline, the two updates) lands as a :code entry on the event's
;; trace. runtime.cljs surfaces those as :debux/code on the epoch
;; (scripts/re_frame_pair/runtime.cljs), which until now was only
;; covered by synthetic-data unit tests. Worked example for the
;; "Trace a handler/sub/fx form-by-form" recipe in SKILL.md.
;;
;; rfp-rsg — also doubles as the worked example for Path 3: this is
;; the only handler in the fixture registered through
;; re-frame-pair.runtime/reg-event-db, so handler-source.sh
;; :event :counter/inc returns {:file :line :column}. The other
;; counters/items/coupon handlers stay on rf/reg-event-db to keep
;; coverage of the :no-source-meta fallback path.
(rfpr/reg-event-db
 :counter/inc
 (fn-traced [db _]
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
