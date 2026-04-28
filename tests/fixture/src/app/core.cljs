(ns app.core
  (:require [reagent.dom.client :as rdc]
            ;; rf-core for the function-only clear-subscription-cache!;
            ;; rf for dispatch-sync's macro form so the bootstrap dispatch
            ;; carries :re-frame/source meta (rf-hsl).
            [re-frame.core      :as rf-core]
            [re-frame.macros    :as rf]
            [app.events]                   ;; side-effecting registrations
            [app.subs]                     ;; "
            [app.views          :as views]))

(defonce root-container
  (rdc/create-root (js/document.getElementById "app")))

(defn mount! []
  (rdc/render root-container [views/root]))

(defn ^:dev/after-load reload! []
  (rf-core/clear-subscription-cache!)
  (mount!))

(defn init []
  (rf/dispatch-sync [:initialize])
  (mount!))
