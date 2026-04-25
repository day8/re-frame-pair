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
      (is (= [{:query-v [:cart/total]}] (:subs/ran e)))
      (is (= [{:query-v [:cart/items]}] (:subs/cache-hit e))))))

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
