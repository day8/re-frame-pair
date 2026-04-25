(ns runtime-test
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame-pair.runtime :as rt]))

;;; Unit tests for the pure fns inside re-frame-pair.runtime.
;;; Runs via shadow-cljs in node — no browser, no live re-frame app needed.
;;; Integration tests requiring a connected browser runtime live in
;;; tests/fixture (see tests/fixture/README.md).

(deftest re-com-classification
  (testing "re-com namespaces are detected by the :re-com? helpers"
    (is (true?  (rt/re-com? "re-com.buttons/button")))
    (is (false? (rt/re-com? "app.views/my-panel")))
    ;; The predicate should short-circuit on non-string input without
    ;; throwing. It returns false (not nil) via `and`'s short-circuit.
    (is (not    (rt/re-com? nil))))

  (testing "category inference covers current re-com namespaces"
    (is (= :input    (rt/re-com-category "re-com.buttons/button")))
    (is (= :input    (rt/re-com-category "re-com.checkbox/checkbox")))
    (is (= :input    (rt/re-com-category "re-com.radio-button/radio-button")))
    (is (= :input    (rt/re-com-category "re-com.input-text/input-text")))
    (is (= :input    (rt/re-com-category "re-com.input-time/input-time")))
    (is (= :input    (rt/re-com-category "re-com.dropdown/dropdown")))
    (is (= :input    (rt/re-com-category "re-com.selection-list/selection-list")))
    (is (= :input    (rt/re-com-category "re-com.multi-select/multi-select")))
    (is (= :input    (rt/re-com-category "re-com.tree-select/tree-select")))
    (is (= :input    (rt/re-com-category "re-com.typeahead/typeahead")))
    (is (= :input    (rt/re-com-category "re-com.datepicker/datepicker")))
    (is (= :input    (rt/re-com-category "re-com.slider/slider")))
    (is (= :input    (rt/re-com-category "re-com.tabs/horizontal-tabs")))
    (is (= :layout   (rt/re-com-category "re-com.box/v-box")))
    (is (= :layout   (rt/re-com-category "re-com.box/h-box")))
    (is (= :layout   (rt/re-com-category "re-com.gap/gap")))
    (is (= :layout   (rt/re-com-category "re-com.scroller/scroller")))
    (is (= :layout   (rt/re-com-category "re-com.splits/h-split")))
    (is (= :table    (rt/re-com-category "re-com.simple-v-table/simple-v-table")))
    (is (= :table    (rt/re-com-category "re-com.v-table/v-table")))
    (is (= :table    (rt/re-com-category "re-com.nested-grid/nested-grid")))
    (is (= :content  (rt/re-com-category "re-com.text/label")))
    (is (= :content  (rt/re-com-category "re-com.typography/title")))
    (is (nil?        (rt/re-com-category "app.views/my-component")))))

(deftest render-entry-classification
  (testing "re-com renders get :re-com? and a :re-com/category"
    (let [classified (rt/classify-render-entry
                      {:component "re-com.buttons/button" :time-ms 1.2})]
      (is (true? (:re-com? classified)))
      (is (= :input (:re-com/category classified)))))

  (testing "non-re-com renders are not annotated"
    (let [classified (rt/classify-render-entry
                      {:component "app.views/my-panel" :time-ms 1.2})]
      (is (nil? (:re-com? classified)))
      (is (nil? (:re-com/category classified))))))

(deftest rc-src-parsing
  (testing "file:line shape — what re-com.debug emits"
    (is (= {:file "app/cart.cljs" :line 42}
           (rt/parse-rc-src "app/cart.cljs:42"))))

  (testing "deeper paths are preserved"
    (is (= {:file "src/main/app/views.cljs" :line 17}
           (rt/parse-rc-src "src/main/app/views.cljs:17"))))

  (testing "malformed attribute"
    (is (nil? (rt/parse-rc-src nil)))
    (is (nil? (rt/parse-rc-src "")))
    (is (nil? (rt/parse-rc-src "not-a-file")))
    (is (nil? (rt/parse-rc-src ":42")))
    (is (nil? (rt/parse-rc-src "app.cljs:")))
    (is (nil? (rt/parse-rc-src "app.cljs:not-a-number")))))

(deftest predicate-matching
  (let [epoch {:id      "e1"
               :event   [:cart/apply-coupon "SPRING25"]
               :time-ms 45
               :app-db/diff {:only-after {:coupon "SPRING25"}}
               :subs/ran [{:query-v [:cart/total] :time-ms 0.3}]
               :renders  [{:component "re-com.buttons/button"}]
               :effects/fired [{:fx-id :db} {:fx-id :dispatch}]}]

    (testing "event-id exact"
      (is (true?  (rt/epoch-matches? {:event-id :cart/apply-coupon} epoch)))
      (is (false? (rt/epoch-matches? {:event-id :cart/other} epoch))))

    (testing "event-id prefix (string-based, so `:cart` matches `:cart/*`)"
      (is (true?  (rt/epoch-matches? {:event-id-prefix :cart} epoch)))
      (is (true?  (rt/epoch-matches? {:event-id-prefix ":cart/"} epoch)))
      (is (false? (rt/epoch-matches? {:event-id-prefix :auth} epoch))))

    (testing "effects present"
      (is (true?  (rt/epoch-matches? {:effects :dispatch} epoch)))
      (is (false? (rt/epoch-matches? {:effects :http-xhrio} epoch))))

    (testing "timing threshold"
      (is (true?  (rt/epoch-matches? {:timing-ms [:> 10]} epoch)))
      (is (false? (rt/epoch-matches? {:timing-ms [:> 100]} epoch))))

    (testing "sub ran"
      (is (true?  (rt/epoch-matches? {:sub-ran :cart/total} epoch)))
      (is (false? (rt/epoch-matches? {:sub-ran :cart/other} epoch))))

    (testing "render included"
      (is (true?  (rt/epoch-matches? {:render "re-com.buttons/button"} epoch)))
      (is (false? (rt/epoch-matches? {:render "app.views/other"} epoch))))

    (testing "compound predicates are AND-ed"
      (is (true?  (rt/epoch-matches? {:event-id-prefix :cart
                                      :sub-ran :cart/total
                                      :render "re-com.buttons/button"}
                                     epoch)))
      (is (false? (rt/epoch-matches? {:event-id-prefix :cart
                                      :sub-ran :auth/user}
                                     epoch))))))

(deftest subs-live-cache-key-extraction
  (testing "extracts :re-frame/query-v from each cache-key map"
    (let [k1 [{:re-frame/query-v   [:cart/total]
               :re-frame/q         :cart/total
               :re-frame/lifecycle :reactive}
              []]
          k2 [{:re-frame/query-v   [:user/profile 42]
               :re-frame/q         :user/profile
               :re-frame/lifecycle :reactive}
              []]]
      (is (= [[:cart/total] [:user/profile 42]]
             (rt/extract-query-vs [k1 k2])))))

  (testing "skips keys without :re-frame/query-v"
    (is (= []
           (rt/extract-query-vs [[{} []]
                                 [{:something :else} []]]))))

  (testing "result is sorted by string-coercion of query-v"
    (let [k1 [{:re-frame/query-v [:zzz/x]} []]
          k2 [{:re-frame/query-v [:aaa/x]} []]
          k3 [{:re-frame/query-v [:mmm/x]} []]]
      (is (= [[:aaa/x] [:mmm/x] [:zzz/x]]
             (rt/extract-query-vs [k1 k2 k3]))))))

(deftest session-sentinel
  (testing "session-id is a UUID string"
    (is (string? rt/session-id))
    (is (= 36 (count rt/session-id)))
    (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                    rt/session-id))))

;; -----------------------------------------------------------------------------
;; coerce-epoch — translates a 10x match record into the §4.3a shape.
;; The test input mirrors what 10x's `tools.metamorphic/parse-traces`
;; assembles: a `:match-info` vector of raw re-frame.trace events.
;; -----------------------------------------------------------------------------

;; A user-event match — :match-info closes at :sync, no render data
;; in its own match. Renders/sub-runs are sourced from the FOLLOWING
;; render-burst match (synthetic-render-burst below), per re-frame +
;; 10x's actual partitioning behavior.

(def ^:private synthetic-match
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
   ;; User-event matches don't carry sub-state with :run? entries; that
   ;; data lives in the next render-burst match.
   :sub-state {:reaction-state {}}
   :timing {:re-frame/event-time 12.5}})

(def ^:private synthetic-render-burst
  ;; The :event nil + :reagent/quiescent match that lands right after
  ;; synthetic-match. carries the sub-state with :run? entries and is
  ;; the id range that contains :render traces.
  {:match-info
   [{:id 120 :op-type :event :tags {}}
    {:id 130 :op-type :reagent/quiescent}]
   :sub-state
   {:reaction-state
    {"rx1" {:subscription [:cart/total]    :order [:sub/run]}
     "rx2" {:subscription [:cart/items]    :order [:sub/run] :sub/traits {:unchanged? true}}
     "rx3" {:subscription [:other/dormant]}}}})

(def ^:private synthetic-traces
  ;; Full trace stream. Renders fall inside the render-burst match's id
  ;; range [120..130]; one outside should be filtered out.
  [{:id 122 :op-type :render :duration 1.4
    :tags {:component-name "re_com.buttons.button" :reaction "rxn-2"}}
   {:id 125 :op-type :render :duration 0.6
    :tags {:component-name "app.views.cart_panel" :reaction "rxn-3"}}
   {:id 200 :op-type :render :duration 0.5
    :tags {:component-name "app.views.outside_match" :reaction "rxn-4"}}])

(deftest coerce-epoch-shape
  (let [ctx {:all-traces synthetic-traces
             :all-matches [synthetic-match synthetic-render-burst]}
        e (rt/coerce-epoch synthetic-match ctx)]
    (testing "id is the first trace's id"
      (is (= 100 (:id e))))
    (testing "timestamp from event trace's :start"
      (is (= 1700000000000 (:t e))))
    (testing "timing from event trace's :duration"
      (is (= 12.5 (:time-ms e))))
    (testing "event vector lifted from event-trace tags"
      (is (= [:cart/apply-coupon "SPRING25"] (:event e))))
    (testing "coeffects + effects pulled through"
      (is (= {:db {:cart {:items []}}} (:coeffects e)))
      (is (map? (:effects e))))
    (testing "interceptor chain — :id keywords only (raw maps carry
              :before/:after function refs that don't edn-roundtrip
              back through cljs-eval)"
      (is (= [:coeffects :db-handler] (:interceptor-chain e))))
    (testing "app-db/diff has clojure.data/diff results"
      (is (some? (:app-db/diff e)))
      (is (= {:cart {:coupon "SPRING25"}} (get-in e [:app-db/diff :only-after])))
      (is (nil? (get-in e [:app-db/diff :only-before]))))
    (testing "subs/ran — reactions that re-ran AND value changed
              (read from :sub-state :reaction-state, not :match-info)"
      (is (= [{:query-v [:cart/total]}] (:subs/ran e))))
    (testing "subs/cache-hit — reactions that re-ran but value was
              :unchanged? (closest signal 10x exposes to a 'cache hit')"
      (is (= [{:query-v [:cart/items]}] (:subs/cache-hit e))))
    (testing "renders — pulled from the full trace stream filtered by
              the match's id range, with munged component names
              demunged to dotted form"
      (is (= 2 (count (:renders e))))
      (let [[btn panel] (:renders e)]
        (is (= "re-com.buttons.button" (:component btn)))
        (is (true? (:re-com? btn)))
        (is (= :input (:re-com/category btn)))
        (is (= "app.views.cart-panel" (:component panel)))
        (is (nil? (:re-com? panel)))))
    (testing "effects/fired flattens the :fx vector and keeps top-level effects"
      (let [fired (:effects/fired e)
            fx-ids (set (map :fx-id fired))]
        (is (contains? fx-ids :db))
        (is (contains? fx-ids :dispatch))
        (is (contains? fx-ids :http-xhrio))))))

(deftest coerce-epoch-handles-nil
  (testing "nil input -> nil"
    (is (nil? (rt/coerce-epoch nil)))))

;; -----------------------------------------------------------------------------
;; Time-travel ops — sentinel checks. Without a connected browser
;; runtime there is no 10x to dispatch into, so we expect the
;; ten-x-missing failure. Real time-travel is exercised by
;; tests/fixture (see tests/fixture/README.md).
;; -----------------------------------------------------------------------------

(deftest undo-without-ten-x
  (testing "navigation ops fail cleanly when 10x is not loaded"
    (is (= :ten-x-missing (:reason (rt/undo-step-back))))
    (is (= :ten-x-missing (:reason (rt/undo-step-forward))))
    (is (= :ten-x-missing (:reason (rt/undo-most-recent))))
    (is (= :ten-x-missing (:reason (rt/undo-replay)))))

  (testing "undo-status reports ten-x-missing"
    (is (= :ten-x-missing (:reason (rt/undo-status)))))

  (testing "undo-to-epoch refuses unknown ids without 10x"
    (let [r (rt/undo-to-epoch 999)]
      ;; 10x missing comes through as either :ten-x-missing (state read failed)
      ;; or :unknown-epoch-id (state read returned nil so no match found).
      ;; Either is correct — the dispatch never attempts.
      (is (false? (:ok? r))))))
