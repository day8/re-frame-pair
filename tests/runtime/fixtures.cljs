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

;; ---------------------------------------------------------------------------
;; Native epoch fixtures (rfp-zl8 / re-frame rf-ybv)
;; ---------------------------------------------------------------------------
;;
;; Mirror what `re-frame.trace/assemble-epochs` produces:
;; one record per `:event` trace, with direct `:child-of` children
;; partitioned by op-type. The trace stream sits beside the epoch so
;; coerce-native-epoch can pull renders / sub-runs by id range.

(def synthetic-native-epoch
  "An assembled epoch as `register-epoch-cb` would deliver it. Mirrors
   synthetic-match's fields where they overlap (same dispatch, same
   :id, same effects shape) so assertions about §4.3a parity can sit
   alongside legacy coerce-epoch tests."
  {:id                 100
   :event              [:cart/apply-coupon "SPRING25"]
   :dispatch-id        "11111111-1111-1111-1111-111111111111"
   :parent-dispatch-id nil
   :app-db/before      {:cart {:items []} :user/profile {:id 1}}
   :app-db/after       {:cart {:items [] :coupon "SPRING25"}
                        :user/profile {:id 1}}
   :coeffects          {:db {:cart {:items []}}}
   :effects            {:db {:cart {:items [] :coupon "SPRING25"}}
                        :fx [[:dispatch [:analytics/track :coupon-applied]]
                             [:http-xhrio {:method :post :uri "/coupon"}]]}
   :interceptors       [{:id :coeffects} {:id :db-handler}]
   :sub-runs           []                ; direct :child-of children — empty in real code
   :sub-creates        []
   :event-handler      {:id 105 :op-type :event/handler :tags {} :child-of 100}
   :event-do-fx        {:id 108 :op-type :event/do-fx   :tags {} :child-of 100}
   :start              1700000000000
   :end                1700000000012
   :duration           12.5})

(def synthetic-native-traces
  "Trace stream slice spanning synthetic-native-epoch's id range
   [100..119]. Carries sub-runs (recomputes during render) with
   :input-query-vs (rf-3p7 item 3 / re-frame commit fa90f70),
   sub-creates with :cached? signals, and renders. Traces with id
   >= 200 are outside the epoch range and should be filtered out
   when coerce-native-epoch looks at this slice in the context of a
   buffer that has a successor epoch starting at 200."
  [{:id 102 :op-type :sub/create :child-of 100
    :tags {:query-v [:cart/total] :cached? false}}
   {:id 103 :op-type :sub/run    :child-of 100
    :tags {:query-v [:cart/total] :input-query-vs [[:cart/items]]}}
   {:id 106 :op-type :sub/create :child-of 105
    :tags {:query-v [:cart/items] :cached? true}}
   {:id 112 :op-type :render :duration 1.4
    :tags {:component-name "re_com.buttons.button" :reaction "rxn-2"}}
   {:id 115 :op-type :render :duration 0.6
    :tags {:component-name "app.views.cart_panel"  :reaction "rxn-3"}}
   {:id 200 :op-type :render :duration 0.5
    :tags {:component-name "app.views.outside_match" :reaction "rxn-4"}}])

(def synthetic-native-next-epoch
  "An epoch that follows synthetic-native-epoch in the buffer.
   Bounds the id range — the render at id 200 above belongs to this
   one, not to synthetic-native-epoch."
  {:id        200
   :event     [:other/event]
   :dispatch-id "22222222-2222-2222-2222-222222222222"
   :start     1700000000050
   :end       1700000000060
   :duration  10.0})

(def native-context
  "Context map for `coerce-native-epoch`'s 2-arg form. The buffer
   carries two epochs so the id range is bounded above."
  {:traces     synthetic-native-traces
   :all-epochs [synthetic-native-epoch synthetic-native-next-epoch]})
