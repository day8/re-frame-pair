(ns app.subs
  (:require [re-frame.macros :as rf]))

;; -----------------------------------------------------------------------------
;; Layer 2 — direct reads from app-db.
;; -----------------------------------------------------------------------------

(rf/reg-sub :counter      (fn [db _] (:counter db)))
(rf/reg-sub :items        (fn [db _] (:items db)))
(rf/reg-sub :coupon       (fn [db _] (:coupon db)))
(rf/reg-sub :events-fired (fn [db _] (:events-fired db)))

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
