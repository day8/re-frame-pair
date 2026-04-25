(ns app.core
  (:require [reagent.dom.client :as rdc]
            [re-frame.core      :as rf]
            [app.events]                   ;; side-effecting registrations
            [app.subs]                     ;; "
            [app.views          :as views]))

(defonce root-container
  (rdc/create-root (js/document.getElementById "app")))

(defn mount! []
  (rdc/render root-container [views/root]))

(defn ^:dev/after-load reload! []
  (rf/clear-subscription-cache!)
  (mount!))

(defn init []
  (rf/dispatch-sync [:initialize])
  (mount!))
