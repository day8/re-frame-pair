(ns app.subs
  (:require [re-frame.core-instrumented :as rf]))

;; -----------------------------------------------------------------------------
;; Layer 2 — direct reads from app-db.
;; -----------------------------------------------------------------------------

(rf/reg-sub :counter      (fn [db _] (:counter db)))
(rf/reg-sub :items        (fn [db _] (:items db)))
(rf/reg-sub :coupon       (fn [db _] (:coupon db)))
(rf/reg-sub :events-fired (fn [db _] (:events-fired db)))

;; Reports — Layer-2 reads. Display surface uses the count + load-via
;; only; the actual rows live in app-db at [:reports :raw] and are
;; reachable for wire-layer drilldown but never rendered (would defeat
;; the demo).
(rf/reg-sub :reports/count
            (fn [db _] (count (get-in db [:reports :raw]))))
(rf/reg-sub :reports/loaded-via
            (fn [db _] (get-in db [:reports :loaded-via])))

;; -----------------------------------------------------------------------------
;; Layer 3 — depend on Layer 2. The spike specifically asks for one
;; Layer 3 sub depending on TWO Layer 2 inputs (so the "why didn't my
;; view update?" recipe has something non-trivial to walk).
;; -----------------------------------------------------------------------------

(rf/reg-sub
 :item-count
 :<- [:items]
 (fn [items _]
   (count items)))

(rf/reg-sub
 :total-qty
 :<- [:items]
 (fn [items _]
   (apply + (map :qty items))))

(rf/reg-sub
 :cart-summary
 :<- [:items]
 :<- [:coupon]
 (fn [[items coupon] _]
   {:item-count (count items)
    :total-qty  (apply + (map :qty items))
    :coupon     (:code coupon)
    :discounted? (= :applied (:status coupon))}))
