(ns runtime-test
  (:require [cljs.test :refer [deftest is testing]]
            [fixtures]
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

;; Synthetic matches + traces live in tests/runtime/fixtures.cljs so a
;; future change to 10x's match shape only updates one place.

(deftest coerce-epoch-shape
  (let [e (rt/coerce-epoch fixtures/synthetic-match fixtures/basic-context)]
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
              (read from :sub-state :reaction-state, not :match-info).
              :input-query-vs is nil here because the synthetic
              all-traces fixture has no :sub/run traces — under live
              fixture conditions it carries the dep graph from
              re-frame's :input-query-vs tag (rfp-fxv / rf-3p7 item 3)."
      (is (= [{:query-v [:cart/total] :input-query-vs nil}] (:subs/ran e))))
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
        (is (contains? fx-ids :http-xhrio))))
    (testing ":debux/code is nil when re-frame-debux didn't write a :code tag"
      (is (nil? (:debux/code e))))))

(deftest coerce-epoch-surfaces-debux-code-when-present
  ;; rfp-hjj item 1: when a fn-traced-wrapped handler runs, debux's
  ;; send-trace! appends per-form trace entries onto the :code tag of
  ;; the current trace event (re-frame-debux/src/day8/re_frame/debux/
  ;; common/util.cljc:132). coerce-epoch should expose that under
  ;; :debux/code so consumers can spot it without colliding with our
  ;; own keys. See docs/inspirations-debux.md §3b.
  (let [code-payload [{:form '(let [n (* 2 x)] (assoc db :n n))
                       :result {:n 10}
                       :indent-level 0
                       :syntax-order 0
                       :num-seen 0}
                      {:form '(* 2 x) :result 10
                       :indent-level 1 :syntax-order 1 :num-seen 0}]
        ;; Synthetic match with the :code tag populated alongside
        ;; :event etc. Mirror the exact shape debux emits.
        match (-> fixtures/synthetic-match
                  (assoc-in [:match-info 0 :tags :code] code-payload))
        e     (rt/coerce-epoch match fixtures/basic-context)]
    (testing ":debux/code surfaces verbatim when present"
      (is (= code-payload (:debux/code e))))
    (testing "other epoch keys are unchanged by the addition"
      (is (= [:cart/apply-coupon "SPRING25"] (:event e)))
      (is (= 100 (:id e))))))

(deftest coerce-epoch-debux-code-empty-vec
  ;; Edge case: debux is loaded but the handler hasn't been wrapped, OR
  ;; was wrapped but emitted no traces (e.g. fn-traced over an empty
  ;; body). An empty vec should pass through unchanged — distinct from
  ;; nil so consumers can tell "wrapped but silent" from "not wrapped".
  (let [match (-> fixtures/synthetic-match
                  (assoc-in [:match-info 0 :tags :code] []))
        e     (rt/coerce-epoch match fixtures/basic-context)]
    (is (= [] (:debux/code e)))))

(deftest coerce-epoch-handles-nil
  (testing "nil input -> nil"
    (is (nil? (rt/coerce-epoch nil)))))

(deftest coerce-epoch-orphaned-event-match
  ;; A user-event match with NO render-burst follower (last item in
  ;; the buffer, page tab inactive, etc.). :renders/:subs/* should be
  ;; nil rather than an empty vec — empty would imply "rendered with
  ;; zero components", which is misleading.
  (let [ctx {:all-traces  []
             :all-matches [fixtures/synthetic-match]} ; no follower
        e   (rt/coerce-epoch fixtures/synthetic-match ctx)]
    (testing "user-event match with no render-burst follower"
      (is (= [:cart/apply-coupon "SPRING25"] (:event e)))
      (is (some? (:app-db/diff e)))
      (is (nil? (:subs/ran e)))
      (is (nil? (:subs/cache-hit e)))
      (is (nil? (:renders e))))))

(deftest coerce-epoch-render-burst-only
  ;; A render-burst match coerced directly. Its own :reagent/quiescent
  ;; close means it satisfies has-render-burst?, so it self-sources
  ;; renders/subs without needing to step forward.
  (let [ctx {:all-traces  fixtures/synthetic-traces
             :all-matches [fixtures/synthetic-render-burst]}
        e   (rt/coerce-epoch fixtures/synthetic-render-burst ctx)]
    (testing "render-burst match self-sources its render data"
      (is (= 2 (count (:renders e))))
      (is (= [{:query-v [:cart/total] :input-query-vs nil}] (:subs/ran e)))
      (is (= [{:query-v [:cart/items]}] (:subs/cache-hit e))))))

;; -----------------------------------------------------------------------------
;; coerce-native-epoch — translate a register-epoch-cb-delivered native
;; epoch (re-frame.trace/assemble-epochs output) into the §4.3a shape.
;; rfp-zl8 / rf-ybv. Pure fn, takes context explicitly.
;; -----------------------------------------------------------------------------

(defn- reset-native-buffers!
  "Reset native-epoch-buffer + native-trace-buffer + cb-installed
   guards to their defonce starting state. Tests do this before each
   scenario so order-of-test doesn't carry state."
  []
  (reset! rt/native-epoch-buffer {:entries [] :max-size 50})
  (reset! rt/native-trace-buffer {:entries [] :max-size 5000}))

(deftest coerce-native-epoch-shape
  (let [e (rt/coerce-native-epoch fixtures/synthetic-native-epoch
                                  fixtures/native-context)]
    (testing "id is the native epoch's :id (the :event trace's id)"
      (is (= 100 (:id e))))
    (testing "timestamps from the native epoch"
      (is (= 1700000000000 (:t e)))
      (is (= 12.5          (:time-ms e))))
    (testing "event vector pulled through"
      (is (= [:cart/apply-coupon "SPRING25"] (:event e))))
    (testing "coeffects + effects pulled through"
      (is (= {:db {:cart {:items []}}} (:coeffects e)))
      (is (map? (:effects e))))
    (testing "interceptor chain — :id keywords only (matches legacy
              coerce-epoch's projection so consumers don't have to
              branch on which epoch source produced the record)"
      (is (= [:coeffects :db-handler] (:interceptor-chain e))))
    (testing "app-db/diff is computed via clojure.data/diff"
      (is (= {:cart {:coupon "SPRING25"}} (get-in e [:app-db/diff :only-after])))
      (is (nil? (get-in e [:app-db/diff :only-before]))))
    (testing "subs/ran is sourced from :sub/run traces in the id range
              (NOT the native epoch's :sub-runs vec, which is empty in
              real code because most :sub/run traces fire from render-
              time derefs with :child-of nil)"
      (is (= [{:query-v [:cart/total] :input-query-vs [[:cart/items]]}]
             (:subs/ran e))))
    (testing "subs/cache-hit is sourced from :sub/create traces with
              :cached? true (re-frame.subs's own signal) — closer to
              §4.3a's 'subs dereffed but cached' than 10x's :unchanged?
              heuristic"
      (is (= [{:query-v [:cart/items]}] (:subs/cache-hit e))))
    (testing "renders are bounded by the next epoch's id (200) — the
              outside-of-range render at id 200 doesn't appear here"
      (is (= 2 (count (:renders e))))
      (let [[btn panel] (:renders e)]
        (is (= "re-com.buttons.button" (:component btn)))
        (is (true? (:re-com? btn)))
        (is (= :input (:re-com/category btn)))
        (is (= "app.views.cart-panel" (:component panel)))
        (is (nil? (:re-com? panel)))))
    (testing "effects/fired flattens :fx and keeps top-level effects"
      (let [fx-ids (set (map :fx-id (:effects/fired e)))]
        (is (contains? fx-ids :db))
        (is (contains? fx-ids :dispatch))
        (is (contains? fx-ids :http-xhrio))))
    (testing ":dispatch-id and :parent-dispatch-id pulled through"
      (is (= "11111111-1111-1111-1111-111111111111" (:dispatch-id e)))
      (is (nil? (:parent-dispatch-id e))))
    (testing ":debux/code is nil when :event-handler trace has no :code tag"
      (is (nil? (:debux/code e))))))

(deftest coerce-native-epoch-handles-nil
  (testing "nil input -> nil"
    (is (nil? (rt/coerce-native-epoch nil)))))

(deftest coerce-native-epoch-debux-code-from-event-handler
  (testing ":debux/code reads from :event-handler trace's :tags
            (where merge-trace! lands when fn-traced-wrapped handlers
            run inside the :event/handler with-trace boundary)"
    (let [code-payload [{:form '(let [n (* 2 x)] (assoc db :n n))
                         :result {:n 10} :indent-level 0
                         :syntax-order 0 :num-seen 0}]
          raw (assoc-in fixtures/synthetic-native-epoch
                        [:event-handler :tags :code] code-payload)
          e   (rt/coerce-native-epoch raw fixtures/native-context)]
      (is (= code-payload (:debux/code e))))))

(deftest coerce-native-epoch-latest-epoch-unbounded
  (testing "the latest epoch in the buffer has no upper-id bound — its
            id range runs to the end of the trace stream, so a render at
            id 200 IS picked up when no successor epoch caps the range"
    (let [ctx {:traces     fixtures/synthetic-native-traces
               :all-epochs [fixtures/synthetic-native-epoch]}    ; no successor
          e   (rt/coerce-native-epoch fixtures/synthetic-native-epoch ctx)]
      (is (= 3 (count (:renders e)))
          "all three :render traces fall in the unbounded id range"))))

(deftest subs-ran-from-native-traces-dedupes-by-query-v
  (testing "multiple :sub/run for the same query-v collapse to the most
            recent (input-deps don't change between runs of the same
            sub, so picking latest is correct)"
    (let [traces [{:id 1 :op-type :sub/run :tags {:query-v [:foo]
                                                  :input-query-vs [[:bar]]}}
                  {:id 2 :op-type :sub/run :tags {:query-v [:foo]
                                                  :input-query-vs [[:bar]]}}
                  {:id 3 :op-type :sub/run :tags {:query-v [:baz]
                                                  :input-query-vs []}}]
          ;; Use a synthetic epoch covering id range [1..10]; coerce
          ;; through coerce-native-epoch to exercise the full path.
          raw   {:id 1 :event [:dummy] :start 0 :end 10 :duration 10
                 :app-db/before {} :app-db/after {} :coeffects {} :effects {}
                 :interceptors []}
          ctx   {:traces traces :all-epochs [raw]}
          ran   (:subs/ran (rt/coerce-native-epoch raw ctx))]
      (is (= 2 (count ran)))
      (is (= #{[:foo] [:baz]} (set (map :query-v ran))))
      (is (= [[:bar]] (:input-query-vs (first (filter #(= [:foo] (:query-v %))
                                                      ran))))))))

(deftest subs-cache-hit-from-native-traces-only-cached-true
  (testing ":sub/create with :cached? true is a cache hit; :cached? false
            is a fresh subscribe (no entry); missing :cached? skips"
    (let [traces [{:id 1 :op-type :sub/create :tags {:query-v [:hit-1]
                                                     :cached? true}}
                  {:id 2 :op-type :sub/create :tags {:query-v [:miss]
                                                     :cached? false}}
                  {:id 3 :op-type :sub/create :tags {:query-v [:hit-2]
                                                     :cached? true}}
                  {:id 4 :op-type :sub/create :tags {:query-v [:no-tag]}}]
          raw   {:id 1 :event [:dummy] :start 0 :end 10 :duration 10
                 :app-db/before {} :app-db/after {} :coeffects {} :effects {}
                 :interceptors []}
          ctx   {:traces traces :all-epochs [raw]}
          hits  (:subs/cache-hit (rt/coerce-native-epoch raw ctx))]
      (is (= 2 (count hits)))
      (is (= #{[:hit-1] [:hit-2]} (set (map :query-v hits)))))))

;; -----------------------------------------------------------------------------
;; Native ring buffers — pure-state checks for the receive-* paths.
;; The cb registration itself is exercised live by tests/fixture; here
;; we confirm the buffer-shape contract under direct swap!s.
;; -----------------------------------------------------------------------------

(deftest native-epoch-buffer-shape
  (testing "native-epoch-buffer starts as {:entries [] :max-size 50}"
    (reset-native-buffers!)
    (is (= []  (:entries @rt/native-epoch-buffer)))
    (is (= 50  (:max-size @rt/native-epoch-buffer)))
    (is (= []  (rt/native-epochs)))))

(deftest native-trace-buffer-shape
  (testing "native-trace-buffer starts as {:entries [] :max-size 5000}"
    (reset-native-buffers!)
    (is (= []   (:entries @rt/native-trace-buffer)))
    (is (= 5000 (:max-size @rt/native-trace-buffer)))
    (is (= []   (rt/native-traces)))))

(deftest find-native-epoch-by-id-from-buffer
  (testing "find-native-epoch-by-id walks the live buffer for a match"
    (reset-native-buffers!)
    (reset! rt/native-epoch-buffer
            {:entries [fixtures/synthetic-native-epoch
                       fixtures/synthetic-native-next-epoch]
             :max-size 50})
    (is (= fixtures/synthetic-native-epoch
           (#'rt/find-native-epoch-by-id 100)))
    (is (= fixtures/synthetic-native-next-epoch
           (#'rt/find-native-epoch-by-id 200)))
    (is (nil? (#'rt/find-native-epoch-by-id 999)))))

(deftest epoch-by-id-prefers-native-buffer
  (testing "epoch-by-id reads from the native buffer when it carries
            the requested id; coerced via coerce-native-epoch (vs
            coerce-epoch which is for the 10x match shape)"
    (reset-native-buffers!)
    (reset! rt/native-epoch-buffer
            {:entries  [fixtures/synthetic-native-epoch]
             :max-size 50})
    (reset! rt/native-trace-buffer
            {:entries  fixtures/synthetic-native-traces
             :max-size 5000})
    (let [e (rt/epoch-by-id 100)]
      (is (some? e))
      (is (= [:cart/apply-coupon "SPRING25"] (:event e)))
      (is (= "11111111-1111-1111-1111-111111111111" (:dispatch-id e)))
      ;; rendered components correlated by id range
      (is (= 3 (count (:renders e)))))))

(deftest last-epoch-prefers-native-buffer
  (testing "last-epoch returns the native buffer's tail when populated;
            no need to consult 10x at all"
    (reset-native-buffers!)
    (reset! rt/native-epoch-buffer
            {:entries  [fixtures/synthetic-native-epoch
                        fixtures/synthetic-native-next-epoch]
             :max-size 50})
    (let [e (rt/last-epoch)]
      (is (= 200 (:id e))) ; the buffer's tail
      (is (= [:other/event] (:event e))))))

(deftest last-claude-epoch-prefers-native-buffer
  (testing "last-claude-epoch matches by :dispatch-id against the
            native buffer first"
    (reset-native-buffers!)
    (reset! rt/native-epoch-buffer
            {:entries  [fixtures/synthetic-native-epoch
                        fixtures/synthetic-native-next-epoch]
             :max-size 50})
    (reset! rt/claude-dispatch-ids
            #{"11111111-1111-1111-1111-111111111111"})
    (let [e (rt/last-claude-epoch)]
      (is (= 100 (:id e)))
      (is (= [:cart/apply-coupon "SPRING25"] (:event e)))))

  (testing "no match in native buffer AND 10x not loaded → nil
            (not a throw — gated on @native-epoch-cb-installed?)"
    (reset-native-buffers!)
    (reset! rt/native-epoch-buffer
            {:entries  [fixtures/synthetic-native-epoch]
             :max-size 50})
    (reset! rt/claude-dispatch-ids #{"some-other-dispatch-id"})
    ;; Pretend the cb is installed so the fallback is gated off when
    ;; 10x is missing — we want nil, not the legacy throw.
    (reset! rt/native-epoch-cb-installed? true)
    (try
      (is (nil? (rt/last-claude-epoch)))
      (finally
        (reset! rt/native-epoch-cb-installed? false)))))

(deftest install-native-epoch-cb-noop-without-register-fn
  (testing "install-native-epoch-cb! is a silent no-op when re-frame
            predates rf-ybv (the runtime-test build pins re-frame
            1.4.5, which doesn't ship register-epoch-cb)"
    (reset! rt/native-epoch-cb-installed? false)
    (rt/install-native-epoch-cb!)
    ;; runtime-test: register-epoch-cb-fn returns nil → no install
    (is (false? @rt/native-epoch-cb-installed?))))

(deftest install-native-trace-cb-idempotent
  (testing "install-native-trace-cb! installs once; second call is no-op"
    (reset! rt/native-trace-cb-installed? false)
    (rt/install-native-trace-cb!)
    (is (true? @rt/native-trace-cb-installed?))
    ;; second call should not re-register; we observe via the guard atom
    (rt/install-native-trace-cb!)
    (is (true? @rt/native-trace-cb-installed?))
    ;; clean up so other tests don't see the registered cb
    (reset! rt/native-trace-cb-installed? false)
    (re-frame.trace/remove-trace-cb :re-frame-pair.runtime/re-frame-pair-traces)))

(deftest health-reports-native-cb-flags
  (testing "health surfaces :native-epoch-cb? and :native-trace-cb?"
    (let [h (rt/health)]
      (is (contains? h :native-epoch-cb?))
      (is (contains? h :native-trace-cb?))
      (is (boolean? (:native-epoch-cb? h)))
      (is (boolean? (:native-trace-cb? h))))))

;; -----------------------------------------------------------------------------
;; rfp-4ew / rf-4mr — dispatch-and-settle bridge for the bash shim.
;; -----------------------------------------------------------------------------
;;
;; The runtime-test build pins re-frame 1.4.5 (predates rf-4mr) so the
;; full dispatch-and-settle! happy path can't run here — exercised in
;; tests/fixture under a re-frame :local/root build. Here we cover:
;;   - feature detection (dispatch-and-settle-fn returns nil → fallback)
;;   - cascade-walking against synthetic native epochs
;;   - await-settle's three return shapes (pending / settled / unknown)

(defn- minimal-native-epoch
  "Bare-bones native epoch with the fields coerce-native-epoch needs.
   Cheaper than fixtures/synthetic-native-epoch for cascade-walk tests
   that don't care about effects / sub-runs / renders."
  [{:keys [id event dispatch-id parent-dispatch-id]}]
  {:id id
   :event event
   :dispatch-id dispatch-id
   :parent-dispatch-id parent-dispatch-id
   :app-db/before {} :app-db/after {} :coeffects {} :effects {}
   :interceptors []
   :start 0 :end id :duration id})

(deftest collect-cascade-from-buffer-walks-parent-chains
  (testing "linear cascade: root → child → grandchild — walks the whole chain"
    (let [epochs [(minimal-native-epoch {:id 1 :event [:root]
                                          :dispatch-id "ROOT"
                                          :parent-dispatch-id nil})
                  (minimal-native-epoch {:id 2 :event [:child]
                                          :dispatch-id "C1"
                                          :parent-dispatch-id "ROOT"})
                  (minimal-native-epoch {:id 3 :event [:grandchild]
                                          :dispatch-id "C2"
                                          :parent-dispatch-id "C1"})]
          {:keys [root-epoch cascaded-epoch-ids cascaded-epochs]}
          (rt/collect-cascade-from-buffer "ROOT" epochs)]
      (is (= [:root] (:event root-epoch)))
      (is (= "ROOT" (:dispatch-id root-epoch)))
      (is (= ["C1" "C2"] cascaded-epoch-ids))
      (is (= [[:child] [:grandchild]] (mapv :event cascaded-epochs)))))

  (testing "fan-out: root → two children — both surface in cascaded-epochs"
    (let [epochs [(minimal-native-epoch {:id 1 :event [:root] :dispatch-id "R"})
                  (minimal-native-epoch {:id 2 :event [:a] :dispatch-id "A" :parent-dispatch-id "R"})
                  (minimal-native-epoch {:id 3 :event [:b] :dispatch-id "B" :parent-dispatch-id "R"})]
          r (rt/collect-cascade-from-buffer "R" epochs)]
      (is (= #{"A" "B"} (set (:cascaded-epoch-ids r))))))

  (testing "unrelated epochs in the buffer are filtered out"
    (let [epochs [(minimal-native-epoch {:id 1 :event [:noise] :dispatch-id "N1"})
                  (minimal-native-epoch {:id 2 :event [:root] :dispatch-id "R"})
                  (minimal-native-epoch {:id 3 :event [:noise2] :dispatch-id "N2"})
                  (minimal-native-epoch {:id 4 :event [:my-child]
                                          :dispatch-id "C" :parent-dispatch-id "R"})]
          r (rt/collect-cascade-from-buffer "R" epochs)]
      (is (= ["C"] (:cascaded-epoch-ids r)))
      (is (= "R" (:dispatch-id (:root-epoch r))))))

  (testing "root not in buffer — :root-epoch nil, no cascade"
    (let [epochs [(minimal-native-epoch {:id 1 :event [:other] :dispatch-id "X"})]
          r (rt/collect-cascade-from-buffer "MISSING" epochs)]
      (is (nil? (:root-epoch r)))
      (is (= [] (:cascaded-epoch-ids r)))))

  (testing "nil root-id returns nil — caller likely had no dispatch-id captured"
    (is (nil? (rt/collect-cascade-from-buffer nil [])))))

(deftest await-settle-state-transitions
  (testing "unknown handle: returns :unknown-handle reason"
    (reset! rt/settle-pending {})
    (let [r (rt/await-settle "no-such-handle")]
      (is (false? (:settled? r)))
      (is (= :unknown-handle (:reason r)))))

  (testing "pending handle: returns {:settled? false :pending? true}"
    (reset! rt/settle-pending {"h1" {:settled? false :event [:foo]}})
    (let [r (rt/await-settle "h1")]
      (is (false? (:settled? r)))
      (is (true? (:pending? r))))
    ;; The atom still holds the entry — pending reads don't clear.
    (is (contains? @rt/settle-pending "h1")))

  (testing "settled handle: returns the resolution map AND removes the entry"
    (reset! rt/settle-pending
            {"h2" {:settled?    true
                   :ok?         true
                   :event       [:foo]
                   :dispatch-id "D"
                   :epoch-id    42
                   :cascaded-epoch-ids ["A" "B"]}})
    (let [r (rt/await-settle "h2")]
      (is (true? (:settled? r)))
      (is (true? (:ok? r)))
      (is (= 42 (:epoch-id r)))
      (is (= ["A" "B"] (:cascaded-epoch-ids r))))
    ;; Subsequent calls should report unknown-handle, not the same record.
    (is (not (contains? @rt/settle-pending "h2")))
    (is (= :unknown-handle (:reason (rt/await-settle "h2"))))))

(deftest dispatch-and-settle-bang-fallback-without-rf-4mr
  (testing "when re-frame predates rf-4mr, dispatch-and-settle! reports
            :dispatch-and-settle-unavailable so the bash shim can fall
            back to tagged-dispatch-sync! + collect-after-dispatch.
            The runtime-test build pins re-frame 1.4.5 — exercises the
            real fallback path, no with-redefs needed."
    (let [r (rt/dispatch-and-settle! [:test/foo])]
      (is (false? (:ok? r)))
      (is (= :dispatch-and-settle-unavailable (:reason r)))
      (is (string? (:hint r))))))

;; -----------------------------------------------------------------------------
;; rfp-zml / rf-ge8 — dispatch-with bridge for safe iteration with
;; record-only stubs. The runtime-test build pins re-frame 1.4.5 so
;; the real dispatch-with! path can't run; we cover the helpers and
;; the feature-detect fallback.
;; -----------------------------------------------------------------------------

(deftest record-only-stub-captures-and-returns-nil
  (testing "the stub appends a log entry and returns nil — the
            original fx's side effect is suppressed"
    (reset! rt/stub-effect-log [])
    (reset! rt/current-who :app)
    (let [stub (rt/record-only-stub :http-xhrio)
          ret  (stub {:method :post :uri "/login"})]
      (is (nil? ret))
      (is (= 1 (count @rt/stub-effect-log)))
      (let [entry (first @rt/stub-effect-log)]
        (is (= :http-xhrio (:fx-id entry)))
        (is (= {:method :post :uri "/login"} (:value entry)))
        (is (number? (:ts entry)))
        (is (= :app (:who entry))))))

  (testing "stub closes over fx-id — distinct ids show in the log"
    (reset! rt/stub-effect-log [])
    ((rt/record-only-stub :navigate) {:to "/dashboard"})
    ((rt/record-only-stub :http-xhrio) {:method :get :uri "/me"})
    (is (= [:navigate :http-xhrio] (mapv :fx-id @rt/stub-effect-log))))

  (testing ":who is read at invocation time, not at construction time
            (so a stub built once and called repeatedly tags whatever
             current-who is when the stub fires)"
    (reset! rt/stub-effect-log [])
    (let [stub (rt/record-only-stub :foo)]
      (reset! rt/current-who :claude)
      (stub :v1)
      (reset! rt/current-who :app)
      (stub :v2))
    (is (= [:claude :app] (mapv :who @rt/stub-effect-log)))))

(deftest build-stub-overrides-shape
  (testing "produces {fx-id stub-fn} with one entry per fx-id"
    (let [m (rt/build-stub-overrides [:http-xhrio :navigate])]
      (is (= #{:http-xhrio :navigate} (set (keys m))))
      (is (every? fn? (vals m)))))

  (testing "empty input → empty map"
    (is (= {} (rt/build-stub-overrides []))))

  (testing "each stub records into the same log when invoked"
    (reset! rt/stub-effect-log [])
    (reset! rt/current-who :claude)
    (let [m (rt/build-stub-overrides [:a :b])]
      ((:a m) "hi")
      ((:b m) {:k 1}))
    (is (= #{:a :b} (set (map :fx-id @rt/stub-effect-log))))))

(deftest stubbed-effects-since-filters-and-tails
  (testing "no-arg form returns everything"
    (reset! rt/stub-effect-log
            [{:fx-id :a :value 1 :ts 100 :who :claude}
             {:fx-id :b :value 2 :ts 200 :who :claude}])
    (let [r (rt/stubbed-effects-since)]
      (is (true? (:ok? r)))
      (is (= 2 (count (:entries r))))
      (is (number? (:now r)))))

  (testing "since-ts filter keeps only entries with :ts >= since-ts
            (a tailing convention — pass back :now from the previous
             call to read only what landed since)"
    (reset! rt/stub-effect-log
            [{:fx-id :a :value 1 :ts 100 :who :claude}
             {:fx-id :b :value 2 :ts 200 :who :claude}
             {:fx-id :c :value 3 :ts 300 :who :claude}])
    (let [r (rt/stubbed-effects-since 200)]
      (is (= [:b :c] (mapv :fx-id (:entries r))))))

  (testing "since-ts past the latest entry returns empty"
    (reset! rt/stub-effect-log
            [{:fx-id :a :value 1 :ts 100 :who :claude}])
    (is (empty? (:entries (rt/stubbed-effects-since 999))))))

(deftest clear-stubbed-effects-empties-the-log
  (reset! rt/stub-effect-log
          [{:fx-id :a :value 1 :ts 100 :who :claude}])
  (is (= {:ok? true} (rt/clear-stubbed-effects!)))
  (is (empty? @rt/stub-effect-log)))

(deftest dispatch-with-bang-fallback-without-rf-ge8
  (testing "runtime-test (re-frame 1.4.5) → :dispatch-with-unavailable"
    (let [r (rt/dispatch-with! [:test/foo] {:http-xhrio (fn [_] nil)})]
      (is (false? (:ok? r)))
      (is (= :dispatch-with-unavailable (:reason r)))
      (is (string? (:hint r)))))

  (testing "dispatch-sync-with! same fallback shape"
    (let [r (rt/dispatch-sync-with! [:test/foo] {:http-xhrio (fn [_] nil)})]
      (is (false? (:ok? r)))
      (is (= :dispatch-sync-with-unavailable (:reason r))))))

(deftest dispatch-with-stubs-bang-builds-overrides
  (testing "dispatch-with-stubs! threads through dispatch-with! — same
            fallback path under runtime-test (no rf-ge8)"
    (let [r (rt/dispatch-with-stubs! [:test/foo] [:http-xhrio :navigate])]
      (is (= :dispatch-with-unavailable (:reason r)))))

  (testing "dispatch-sync-with-stubs! mirrors"
    (let [r (rt/dispatch-sync-with-stubs! [:test/foo] [:http-xhrio])]
      (is (= :dispatch-sync-with-unavailable (:reason r))))))

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

;; -----------------------------------------------------------------------------
;; Edge cases (rfp-czf H) — cheap pure-fn coverage so future shape
;; drifts (re-com namespace tweaks, re-frame cache-key changes,
;; new component categories, etc.) get caught at unit-test time.
;; -----------------------------------------------------------------------------

(deftest re-com-classification-edges
  (testing "non-string / empty inputs short-circuit cleanly"
    (is (not (rt/re-com? "")))
    (is (not (rt/re-com? 42)))
    (is (not (rt/re-com? :keyword))))

  (testing "non-re-com namespace returns nil category"
    (is (nil? (rt/re-com-category "app.user-profile/avatar")))
    ;; A name that contains 're-com.' as a substring but doesn't
    ;; START with it should not classify.
    (is (nil? (rt/re-com-category "wrap.re-com.fake/x"))))

  (testing "exact `re-com.box.box` (no slash) still classifies as :layout"
    ;; Reagent's :component-name comes through demunged but not
    ;; slashed (e.g. `re-com.box.box`). The category heuristic uses
    ;; substring matching, so this flows correctly.
    (is (= :layout (rt/re-com-category "re-com.box.box")))))

(deftest rc-src-parsing-edges
  (testing "Windows-style paths with drive-letter colon"
    ;; Last colon is the line separator, earlier colons are part of
    ;; the file path. parse-rc-src uses last-index-of so this works.
    (is (= {:file "C:\\src\\app\\views.cljs" :line 17}
           (rt/parse-rc-src "C:\\src\\app\\views.cljs:17")))
    (is (= {:file "D:\\proj:weird\\name.cljs" :line 9}
           (rt/parse-rc-src "D:\\proj:weird\\name.cljs:9"))))

  (testing "multi-digit lines"
    (is (= {:file "f.cljs" :line 12345} (rt/parse-rc-src "f.cljs:12345"))))

  (testing "adjacent colons (file portion ends with `:`)"
    ;; Last colon is still the separator. file-part is `f::`, line=4.
    (is (= {:file "f::" :line 4} (rt/parse-rc-src "f:::4")))))

(deftest epoch-matches-completeness
  (let [epoch {:event   [:cart/apply-coupon "X"]
               :time-ms 50
               :app-db/diff {:only-after {:cart {:coupon "X"}}}
               :subs/ran [{:query-v [:cart/total]}]
               :renders  [{:component "re-com.buttons/button"}]
               :effects/fired [{:fx-id :db} {:fx-id :dispatch}]}]
    (testing "empty pred matches anything"
      (is (true? (rt/epoch-matches? {} epoch))))

    (testing "event-id requires exact-equal"
      (is (true?  (rt/epoch-matches? {:event-id :cart/apply-coupon} epoch)))
      (is (false? (rt/epoch-matches? {:event-id :cart/other} epoch))))

    (testing "event-id-prefix is a string-form prefix match"
      (is (true?  (rt/epoch-matches? {:event-id-prefix :cart} epoch)))
      (is (true?  (rt/epoch-matches? {:event-id-prefix ":cart/apply"} epoch)))
      (is (false? (rt/epoch-matches? {:event-id-prefix :other} epoch))))

    (testing "effects: fx-id present in :effects/fired"
      (is (true?  (rt/epoch-matches? {:effects :db} epoch)))
      (is (false? (rt/epoch-matches? {:effects :http-xhrio} epoch))))

    (testing "timing-ms operators"
      (is (true?  (rt/epoch-matches? {:timing-ms [:> 10]} epoch)))
      (is (false? (rt/epoch-matches? {:timing-ms [:> 100]} epoch)))
      (is (true?  (rt/epoch-matches? {:timing-ms [:< 100]} epoch)))
      (is (false? (rt/epoch-matches? {:timing-ms [:< 10]} epoch))))

    (testing "touches-path: non-empty walks (get-in only-before/only-after path)"
      (is (true?  (rt/epoch-matches? {:touches-path [:cart :coupon]} epoch)))
      (is (false? (rt/epoch-matches? {:touches-path [:user :id]} epoch))))

    (testing "touches-path: empty path = any non-empty diff (G3 fix)"
      (is (true? (rt/epoch-matches? {:touches-path []} epoch)))
      (let [no-diff (assoc epoch :app-db/diff {:only-before nil :only-after nil})]
        (is (false? (rt/epoch-matches? {:touches-path []} no-diff)))))

    (testing "compound predicates AND together"
      (is (true?  (rt/epoch-matches? {:event-id-prefix :cart
                                      :sub-ran :cart/total
                                      :render "re-com.buttons/button"}
                                     epoch)))
      (is (false? (rt/epoch-matches? {:event-id-prefix :cart
                                      :sub-ran :nope}
                                     epoch))))

    (testing "sparse epoch fields don't crash predicates"
      ;; Epoch missing :renders / :subs/ran shouldn't throw.
      (let [bare {:event [:foo]}]
        (is (false? (rt/epoch-matches? {:render "x"} bare)))
        (is (false? (rt/epoch-matches? {:sub-ran :y} bare)))
        (is (false? (rt/epoch-matches? {:effects :z} bare)))))))

(deftest extract-query-vs-edges
  (testing "duplicate query-v entries are kept (different dyn-vec contexts)"
    (let [k1 [{:re-frame/query-v [:foo]} []]
          k2 [{:re-frame/query-v [:foo]} ["a"]]]
      (is (= [[:foo] [:foo]] (rt/extract-query-vs [k1 k2])))))

  (testing "malformed cache-key entries (no :re-frame/query-v) are skipped"
    (is (= [[:bar]]
           (rt/extract-query-vs [[{} []]
                                 [{:something :else} []]
                                 [{:re-frame/query-v [:bar]} []]]))))

  (testing "empty cache returns empty vec"
    (is (= [] (rt/extract-query-vs [])))
    (is (= [] (rt/extract-query-vs nil)))))

(deftest version-below-edges
  (testing "strict-below dotted-number comparison"
    (is (true?  (rt/version-below? "1.4" "1.5")))
    (is (false? (rt/version-below? "1.5" "1.4")))
    (is (false? (rt/version-below? "1.4" "1.4"))))

  (testing "mismatched part-counts: shorter compared as if zero-padded"
    ;; "2.20.0" vs "2.21" — 0 < 1, so "2.20.0" < "2.21".
    (is (true?  (rt/version-below? "2.20.0" "2.21")))
    (is (false? (rt/version-below? "2.21" "2.20.0"))))

  (testing "non-numeric prefixes / alpha tags: re-seq picks digit runs"
    ;; "1.4.0-rc1" → digit-runs [1 4 0 1]. "1.4.0" → [1 4 0]. The
    ;; rc version sorts AS IF GREATER (the trailing 1 makes it so) —
    ;; that's a known quirk of digit-run comparison; document it
    ;; rather than attempt true semver here.
    (is (false? (rt/version-below? "1.4.0-rc1" "1.4.0"))))

  (testing "nil / :unknown observed or floor: cannot enforce, returns false"
    (is (false? (rt/version-below? nil "1.0")))
    (is (false? (rt/version-below? "1.0" nil)))
    (is (false? (rt/version-below? :unknown "1.0")))
    (is (false? (rt/version-below? "1.0" :unknown)))))

;; ---------------------------------------------------------------------------
;; Tests for rfp-r5s A: console.* capture
;; ---------------------------------------------------------------------------
;;
;; These tests exist because rfp-r5s A shipped without them, and the rfp-r5s
;; D forward-reference regression demonstrated that a green `npm test` is no
;; guarantee a code addition is exercised. See tests/ops_smoke.bb for the
;; babashka-side load-smoke partner.

(defn- reset-console-log!
  "Reset the console-log atom and the :who pointer to the same shape
   the runtime defonce starts at. Tests do this before each scenario
   so order doesn't matter."
  []
  (reset! rt/console-log {:entries [] :next-id 0 :max-size 500})
  (reset! rt/current-who :app))

(deftest console-tail-since-empty-buffer
  (testing "console-tail-since on a fresh buffer returns no entries"
    (reset-console-log!)
    (let [r (rt/console-tail-since 0)]
      (is (true? (:ok? r)))
      (is (= [] (:entries r)))
      (is (= 0  (:next-id r)))
      (is (= 500 (:max-size r))))))

(deftest console-tail-since-id-filter
  (testing "since-id selects only entries with :id >= since-id"
    (reset-console-log!)
    ;; Seed the atom directly so we don't depend on the JS console wrap
    ;; in the node-test environment. The capture pipeline ends up calling
    ;; the same swap! the wrapper does — this tests the read seam.
    (reset! rt/console-log
            {:entries  [{:id 0 :ts 100 :level :log   :args ["one"]   :who :app    :stack nil}
                        {:id 1 :ts 110 :level :info  :args ["two"]   :who :claude :stack nil}
                        {:id 2 :ts 120 :level :warn  :args ["three"] :who :app    :stack "stk"}
                        {:id 3 :ts 130 :level :error :args ["four"]  :who :handler-error :stack "boom"}]
             :next-id  4
             :max-size 500})
    (is (= [0 1 2 3] (mapv :id (:entries (rt/console-tail-since 0)))))
    (is (= [2 3]     (mapv :id (:entries (rt/console-tail-since 2)))))
    (is (= []        (mapv :id (:entries (rt/console-tail-since 4)))))
    (is (= 4         (:next-id (rt/console-tail-since 0))))))

(deftest console-tail-since-who-filter
  (testing "who arg picks only entries tagged with that :who"
    (reset-console-log!)
    (reset! rt/console-log
            {:entries  [{:id 0 :who :app    :level :log :args [] :ts 0 :stack nil}
                        {:id 1 :who :claude :level :log :args [] :ts 0 :stack nil}
                        {:id 2 :who :app    :level :log :args [] :ts 0 :stack nil}
                        {:id 3 :who :handler-error :level :error :args [] :ts 0 :stack nil}]
             :next-id  4
             :max-size 500})
    (is (= [0 2] (mapv :id (:entries (rt/console-tail-since 0 :app)))))
    (is (= [1]   (mapv :id (:entries (rt/console-tail-since 0 :claude)))))
    (is (= [3]   (mapv :id (:entries (rt/console-tail-since 0 :handler-error)))))
    ;; nil who → no who-filter, all entries returned
    (is (= [0 1 2 3] (mapv :id (:entries (rt/console-tail-since 0 nil)))))))

(deftest console-tail-since-id-and-who-combined
  (testing "since-id + who AND-combine"
    (reset-console-log!)
    (reset! rt/console-log
            {:entries  [{:id 0 :who :app    :level :log :args [] :ts 0 :stack nil}
                        {:id 1 :who :claude :level :log :args [] :ts 0 :stack nil}
                        {:id 2 :who :app    :level :log :args [] :ts 0 :stack nil}
                        {:id 3 :who :claude :level :log :args [] :ts 0 :stack nil}]
             :next-id  4
             :max-size 500})
    (is (= [3] (mapv :id (:entries (rt/console-tail-since 2 :claude))))
        ":id >= 2 AND :who :claude")))

;; The append + ring-buffer trim is exercised end-to-end via
;; tagged-dispatch-sync!'s :handler-threw catch — register a throwing
;; handler, dispatch it, and verify the synthesised entry. This tests the
;; SAME swap! the console wrapper uses, without depending on js/console
;; being wrappable in the node-test environment.

(deftest tagged-dispatch-sync-bang-synthesises-handler-error-entry
  (testing "tagged-dispatch-sync! catch appends a :handler-error console entry"
    (reset-console-log!)
    (re-frame.core/reg-event-db
     :test/throws-on-purpose
     (fn [_db _ev] (throw (ex-info "boom" {:why :test}))))
    (let [r    (rt/tagged-dispatch-sync! [:test/throws-on-purpose])
          tail (rt/console-tail-since 0 :handler-error)]
      (is (false?              (:ok? r)))
      (is (= :handler-threw    (:reason r)))
      (is (= 1                 (count (:entries tail))))
      (let [e (first (:entries tail))]
        (is (= :error          (:level e)))
        (is (= :handler-error  (:who e)))
        (is (string?           (:stack e)))
        ;; Args carry the formatted '[handler-threw]' message + event vec.
        (is (some #(re-find #"handler-threw" %) (:args e)))))))

(deftest tagged-dispatch-sync-bang-restores-current-who-on-catch
  (testing "even when the handler throws, current-who is restored to :app"
    (reset-console-log!)
    (reset! rt/current-who :app)
    (re-frame.core/reg-event-db
     :test/throws-on-purpose-2
     (fn [_db _ev] (throw (ex-info "boom" {}))))
    (rt/tagged-dispatch-sync! [:test/throws-on-purpose-2])
    (is (= :app @rt/current-who)
        "the (try ... (finally (reset! current-who :app))) wrapper must run on every exit")))

(deftest tagged-dispatch-sync-bang-success-path-restores-who
  (testing "after a successful dispatch, current-who is :app again"
    (reset-console-log!)
    (reset! rt/current-who :app)
    (re-frame.core/reg-event-db
     :test/no-op
     (fn [db _ev] db))
    (rt/tagged-dispatch-sync! [:test/no-op])
    (is (= :app @rt/current-who))))

;; ---------------------------------------------------------------------------
;; Tests for rfp-r5s B: app/summary
;; ---------------------------------------------------------------------------

(deftest value-shape-tag-dispatch
  (testing "value-shape-tag returns a symbol describing the type"
    (is (= 'nil     (rt/value-shape-tag nil)))
    (is (= 'map     (rt/value-shape-tag {:a 1})))
    (is (= 'map     (rt/value-shape-tag {})))
    (is (= 'vec     (rt/value-shape-tag [])))
    (is (= 'vec     (rt/value-shape-tag [1 2 3])))
    (is (= 'set     (rt/value-shape-tag #{:a :b})))
    (is (= 'string  (rt/value-shape-tag "hello")))
    (is (= 'boolean (rt/value-shape-tag true)))
    (is (= 'boolean (rt/value-shape-tag false)))
    (is (= 'keyword (rt/value-shape-tag :foo)))
    (is (= 'number  (rt/value-shape-tag 42)))
    (is (= 'number  (rt/value-shape-tag 3.14)))
    (is (= 'other   (rt/value-shape-tag 'symbol)))
    ;; Lists and seqs are :sequential? but not vector/set, so 'seq
    (is (= 'seq     (rt/value-shape-tag '(1 2 3))))
    (is (= 'seq     (rt/value-shape-tag (range 3))))))

(deftest value-shape-tag-precedence
  (testing "map dispatch beats sequential? for ordered records"
    ;; Records are maps; cond-order should match :map first.
    (is (= 'map (rt/value-shape-tag {:k :v}))))

  (testing "vector beats sequential — both predicates would match"
    (is (= 'vec (rt/value-shape-tag [:a :b])))))

;; The full app-summary builder calls into version-report,
;; registrar-describe, subs-live, and health. health installs the
;; last-click and console-capture wrappers, which reach for js/window;
;; in shadow-cljs's :node-test build js/window is bound to a node-side
;; shim so the calls don't throw. The shape test below confirms the
;; bundle returns the expected top-level keys.

(deftest app-summary-shape
  (testing "app-summary returns the bootstrap-bundle shape"
    (let [s (rt/app-summary)]
      (is (true? (:ok? s)))
      (is (contains? s :versions))
      (is (contains? s :registrar))
      (is (contains? s :live-subs))
      (is (contains? s :app-db-keys))
      (is (contains? s :app-db-shape))
      (is (contains? s :health))
      (is (number? (:ts s)))
      ;; :registrar must be the by-kind map (extracted from
      ;; registrar-describe — not the raw envelope).
      (is (map? (:registrar s)))
      ;; live-subs is a vec of query-vectors (possibly empty).
      (is (vector? (:live-subs s)))
      ;; health is itself a map with :ok? true.
      (is (true? (-> s :health :ok?))))))

(deftest app-summary-app-db-shape-vs-non-map
  (testing "when @app-db is not a map, :app-db-keys / :app-db-shape are nil"
    ;; Save and restore re-frame.db/app-db so other tests aren't disturbed.
    (let [orig @re-frame.db/app-db]
      (try
        (reset! re-frame.db/app-db [:not :a :map])
        (let [s (rt/app-summary)]
          (is (nil? (:app-db-keys s)))
          (is (nil? (:app-db-shape s))))
        (finally
          (reset! re-frame.db/app-db orig))))))

(deftest app-summary-app-db-shape-is-one-level-deep
  (testing "with a structured app-db, :app-db-shape returns top-level type tags"
    (let [orig @re-frame.db/app-db]
      (try
        (reset! re-frame.db/app-db
                {:counter 0
                 :items   [{:id 1} {:id 2}]
                 :user    {:id 99 :name "alice"}
                 :flags   #{:a :b}
                 :note    "hi"})
        (let [s (rt/app-summary)]
          (is (= '{:counter number
                   :items   vec
                   :user    map
                   :flags   set
                   :note    string}
                 (:app-db-shape s)))
          (is (= #{:counter :items :user :flags :note}
                 (set (:app-db-keys s)))))
        (finally
          (reset! re-frame.db/app-db orig))))))

;; ---------------------------------------------------------------------------
;; Tests for rfp-r5s C: handler/source
;; ---------------------------------------------------------------------------
;;
;; handler-source reads the metadata that ClojureScript's source-map machinery
;; would attach to a compiled handler fn. To test it without depending on the
;; compiler-time meta, we synthesize handlers with explicit `(with-meta f m)`
;; and register them directly into re-frame's registrar atom — bypassing
;; reg-sub / reg-event-db's wrapping so we can verify handler-source's
;; lookup + drill-down logic in isolation.

(defn- with-registered
  "Register `handler` under [kind id] for the body, then dissoc.
   Lets the body run without polluting other tests' registrar state."
  [kind id handler body-fn]
  (let [r re-frame.registrar/kind->id->handler]
    (swap! r assoc-in [kind id] handler)
    (try (body-fn)
         (finally (swap! r update kind dissoc id)))))

(deftest handler-source-sub-kind-with-meta
  (testing ":sub stored value IS the user fn — meta surfaces directly"
    (with-registered :sub :test/sub-with-meta
      (with-meta (fn [_]) {:file "foo.cljs" :line 42 :column 3})
      (fn []
        (let [r (rt/handler-source :sub :test/sub-with-meta)]
          (is (true? (:ok? r)))
          (is (= :sub               (:kind r)))
          (is (= :test/sub-with-meta (:id r)))
          (is (= "foo.cljs"          (:file r)))
          (is (= 42                  (:line r)))
          (is (= 3                   (:column r))))))))

(deftest handler-source-fx-kind
  (testing ":fx kind looks at the stored fn's meta directly"
    (with-registered :fx :test/fx-with-meta
      (with-meta (fn [_]) {:file "fx.cljs" :line 10})
      (fn []
        (let [r (rt/handler-source :fx :test/fx-with-meta)]
          (is (true? (:ok? r)))
          (is (= "fx.cljs" (:file r)))
          (is (= 10        (:line r)))
          ;; column not provided → present as nil
          (is (nil?        (:column r))))))))

(deftest handler-source-event-drills-into-terminal-interceptor
  (testing ":event stored value is an interceptor chain — drill into terminal :before"
    (let [user-fn (with-meta (fn [_db _ev]) {:file "events.cljs" :line 99})
          chain   [{:id :coeffects} {:id :do-fx} {:id :db-handler :before user-fn}]]
      (with-registered :event :test/event-with-meta chain
        (fn []
          (let [r (rt/handler-source :event :test/event-with-meta)]
            (is (true? (:ok? r)))
            (is (= "events.cljs" (:file r)))
            (is (= 99            (:line r)))))))))

(deftest handler-source-no-source-meta
  (testing "registered handler with no meta returns :no-source-meta cleanly"
    (with-registered :sub :test/sub-no-meta
      (fn [_])
      (fn []
        (let [r (rt/handler-source :sub :test/sub-no-meta)]
          (is (false?           (:ok? r)))
          (is (= :no-source-meta (:reason r)))
          (is (= :sub            (:kind r)))
          (is (= :test/sub-no-meta (:id r)))
          ;; No throw, no leaked exception data.
          (is (not (contains? r :error)))
          (is (not (contains? r :file))))))))

(deftest handler-source-not-registered
  (testing "id that's not in the registrar returns :not-registered"
    (let [r (rt/handler-source :sub :test/never-registered-i-promise)]
      (is (false?            (:ok? r)))
      (is (= :not-registered (:reason r)))
      (is (= :sub            (:kind r)))
      (is (= :test/never-registered-i-promise (:id r))))))

(deftest handler-source-event-with-no-meta-on-terminal-fn
  (testing "event with terminal :before lacking meta returns :no-source-meta"
    (let [chain [{:id :coeffects} {:id :db-handler :before (fn [_db _ev])}]]
      (with-registered :event :test/event-no-meta chain
        (fn []
          (let [r (rt/handler-source :event :test/event-no-meta)]
            (is (false? (:ok? r)))
            (is (= :no-source-meta (:reason r)))))))))

(deftest handler-source-kind-with-empty-meta-map
  (testing "meta map exists but has no :file / :line keys returns :no-source-meta"
    (with-registered :sub :test/sub-empty-meta
      (with-meta (fn [_]) {:doc "I have meta but nothing useful"})
      (fn []
        (let [r (rt/handler-source :sub :test/sub-empty-meta)]
          (is (false? (:ok? r)))
          (is (= :no-source-meta (:reason r))))))))
