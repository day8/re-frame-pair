(ns fixtures)

;;; Shared synthetic data for runtime unit tests. Pulled out so future
;;; shape changes (10x match-info skeleton, sub-state structure, etc.)
;;; only need updating in one place.

(def synthetic-match
  "User-event match. Closes at :sync (not :reagent/quiescent), so its
   own sub-state has no :run? entries — render data has to come from
   the next match."
  {:match-info
   [{:id 100 :op-type :event
     :start 1700000000000
     :duration 12.5
     :tags {:event         [:cart/apply-coupon "SPRING25"]
            :app-db-before {:cart {:items []} :user/profile {:id 1}}
            :app-db-after  {:cart {:items [] :coupon "SPRING25"} :user/profile {:id 1}}
            :coeffects     {:db {:cart {:items []}}}
            :effects       {:db {:cart {:items [] :coupon "SPRING25"}}
                            :fx [[:dispatch [:analytics/track :coupon-applied]]
                                 [:http-xhrio {:method :post :uri "/coupon"}]]}
            :interceptors  [{:id :coeffects} {:id :db-handler}]}}
    {:id 110 :op-type :sync}]
   :sub-state {:reaction-state {}}
   :timing    {:re-frame/event-time 12.5}})

(def synthetic-render-burst
  "The :event nil + :reagent/quiescent match that lands right after
   synthetic-match. Carries the :sub-state with :run? entries and is
   the id range that contains :render traces."
  {:match-info
   [{:id 120 :op-type :event :tags {}}
    {:id 130 :op-type :reagent/quiescent}]
   :sub-state
   {:reaction-state
    {"rx1" {:subscription [:cart/total]    :order [:sub/run]}
     "rx2" {:subscription [:cart/items]    :order [:sub/run] :sub/traits {:unchanged? true}}
     "rx3" {:subscription [:other/dormant]}}}})

(def synthetic-traces
  "Full trace stream that would live at (:all (:traces db)). Two
   renders fall inside the render-burst's id range [120..130]; one
   outside is filtered out."
  [{:id 122 :op-type :render :duration 1.4
    :tags {:component-name "re_com.buttons.button" :reaction "rxn-2"}}
   {:id 125 :op-type :render :duration 0.6
    :tags {:component-name "app.views.cart_panel" :reaction "rxn-3"}}
   {:id 200 :op-type :render :duration 0.5
    :tags {:component-name "app.views.outside_match" :reaction "rxn-4"}}])

(def basic-context
  "Context map for `coerce-epoch`'s 2-arg form, paired with
   synthetic-match + synthetic-render-burst + synthetic-traces."
  {:all-traces  synthetic-traces
   :all-matches [synthetic-match synthetic-render-burst]})
