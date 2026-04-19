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

  (testing "category inference is best-effort but reasonable"
    (is (= :input   (rt/re-com-category "re-com.buttons/button")))
    (is (= :layout  (rt/re-com-category "re-com.box/v-box")))
    (is (= :content (rt/re-com-category "re-com.text/label")))
    (is (nil?       (rt/re-com-category "app.views/my-component")))))

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
  (testing "single-line attribute"
    (is (= {:file "app/cart.cljs" :line 42 :column nil}
           (rt/parse-rc-src "app/cart.cljs:42"))))

  (testing "file:line:column attribute"
    (is (= {:file "app/cart.cljs" :line 42 :column 8}
           (rt/parse-rc-src "app/cart.cljs:42:8"))))

  (testing "malformed attribute"
    (is (nil? (rt/parse-rc-src nil)))
    (is (nil? (rt/parse-rc-src "")))
    (is (nil? (rt/parse-rc-src "not-a-file")))))

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

(deftest session-sentinel
  (testing "session-id is a UUID string"
    (is (string? rt/session-id))
    (is (= 36 (count rt/session-id)))
    (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                    rt/session-id))))

(deftest time-travel-stubs
  (testing "each undo op is a uniform :not-yet-implemented stub until the spike"
    (is (= :not-yet-implemented (:reason (rt/undo-step-back))))
    (is (= :not-yet-implemented (:reason (rt/undo-step-forward))))
    (is (= :not-yet-implemented (:reason (rt/undo-to-epoch "x"))))
    (is (= :not-yet-implemented (:reason (rt/undo-status))))))
