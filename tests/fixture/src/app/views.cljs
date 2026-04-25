(ns app.views
  (:require [re-frame.core :as rf]
            [reagent.core  :as reagent]
            [re-com.core   :refer [at v-box h-box box gap label title button
                                   input-text line]]))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- panel-title [text]
  [title :src (at)
   :label text
   :level :level3
   :margin-top "12px"
   :margin-bottom "4px"])

;; -----------------------------------------------------------------------------
;; Counter — h-box with +/- buttons. The simplest visible state change.
;; -----------------------------------------------------------------------------

(defn counter-panel []
  (let [n @(rf/subscribe [:counter])]
    [v-box :src (at)
     :gap "6px"
     :children
     [[panel-title "Counter"]
      [h-box :src (at)
       :gap "8px"
       :align :center
       :children
       [[button :src (at)
         :label "−"
         :on-click #(rf/dispatch [:counter/dec])]
        [box :src (at)
         :width "40px"
         :align-self :center
         :child [label :src (at) :label (str n)]]
        [button :src (at)
         :label "+"
         :on-click #(rf/dispatch [:counter/inc])]
        [gap :src (at) :size "12px"]
        [button :src (at)
         :label "reset"
         :on-click #(rf/dispatch [:counter/reset])]]]]]))

;; -----------------------------------------------------------------------------
;; Items list — for each item, a h-box with name, qty, and +1 button.
;; Uses :items Layer 2 sub directly.
;; -----------------------------------------------------------------------------

(defn item-row [{:keys [id name qty]}]
  [h-box :src (at)
   :gap "12px"
   :align :center
   :children
   [[box :src (at) :width "80px"  :child [label :src (at) :label name]]
    [box :src (at) :width "30px"  :child [label :src (at) :label (str qty)]]
    [button :src (at)
     :label "+1"
     :on-click #(rf/dispatch [:item/inc-qty id])]]])

(defn items-panel []
  (let [items @(rf/subscribe [:items])]
    [v-box :src (at)
     :gap "4px"
     :children
     (into [[panel-title "Items"]]
           (for [item items]
             ^{:key (:id item)} [item-row item]))]))

;; -----------------------------------------------------------------------------
;; Coupon — input-text + apply button. Exercises reg-event-fx with
;; an internal :dispatch effect (good shape for the trace recipe).
;; -----------------------------------------------------------------------------

(defn coupon-panel []
  ;; form-2: keep `draft` (a local reagent ratom) alive across
  ;; re-renders, but deref the re-frame sub inside the render fn so
  ;; it stays reactive to coupon changes.
  (let [draft (reagent/atom "")]
    (fn []
      (let [coupon @(rf/subscribe [:coupon])]
        [v-box :src (at)
         :gap "6px"
         :children
         [[panel-title "Coupon"]
          [h-box :src (at)
           :gap "8px"
           :align :center
           :children
           [[input-text :src (at)
             :model       @draft
             :on-change   #(reset! draft %)
             :width       "120px"
             :placeholder "code…"
             :change-on-blur? false]
            [button :src (at)
             :label "Apply"
             :on-click #(rf/dispatch [:coupon/apply @draft])]
            [gap :src (at) :size "12px"]
            [label :src (at)
             :label (case (:status coupon)
                      :applied (str "applied: " (:code coupon))
                      :none    "(none)"
                      "(unknown)")]]]]]))))

;; -----------------------------------------------------------------------------
;; Cart summary — the Layer-3 sub depending on two Layer-2s. Spike
;; recipe "why didn't my view update?" walks this.
;; -----------------------------------------------------------------------------

(defn summary-panel []
  (let [{:keys [item-count total-qty coupon discounted?]} @(rf/subscribe [:cart-summary])]
    [v-box :src (at)
     :gap "4px"
     :children
     [[panel-title "Cart summary (Layer 3 sub)"]
      [label :src (at) :label (str "items: " item-count)]
      [label :src (at) :label (str "total qty: " total-qty)]
      [label :src (at) :label (str "coupon: " (if (seq coupon) coupon "(none)"))]
      [label :src (at) :label (str "discounted? " discounted?)]]]))

;; -----------------------------------------------------------------------------
;; Broken handlers panel — dispatchable bugs. The "experiment loop"
;; recipe in SKILL.md iterates against these.
;; -----------------------------------------------------------------------------

(defn broken-panel []
  [v-box :src (at)
   :gap "6px"
   :children
   [[panel-title "Deliberately broken handlers"]
    [h-box :src (at)
     :gap "8px"
     :children
     [[button :src (at)
       :label "throw in handler"
       :on-click #(rf/dispatch [:broken/throw])]
      [button :src (at)
       :label "return non-map"
       :on-click #(rf/dispatch [:broken/non-map])]
      [button :src (at)
       :label "reset to initial-db"
       :on-click #(rf/dispatch [:initialize])]]]]])

;; -----------------------------------------------------------------------------
;; Telemetry footer
;; -----------------------------------------------------------------------------

(defn footer []
  (let [n @(rf/subscribe [:events-fired])]
    [v-box :src (at)
     :gap "4px"
     :children
     [[line :src (at)]
      [label :src (at)
       :label (str "events fired this session: " n)
       :class "muted"]]]))

;; -----------------------------------------------------------------------------
;; Root
;; -----------------------------------------------------------------------------

(defn root []
  [box :src (at)
   :class "app-shell"
   :child
   [v-box :src (at)
    :gap "16px"
    :width  "640px"
    :children
    [[title :src (at)
      :label "re-frame-pair fixture"
      :level :level1]
     [label :src (at)
      :label "Subject app for the v0.1.0-beta.1 spike. Click around — every action shows up in re-frame-10x and is reachable from re-frame-pair runtime ops."
      :class "muted"]
     [counter-panel]
     [items-panel]
     [coupon-panel]
     [summary-panel]
     [broken-panel]
     [footer]]]])
