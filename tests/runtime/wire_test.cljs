(ns wire-test
  (:require [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame-pair.runtime.wire :as wire]))

;; The wire ns owns a session-scoped result store. Each test resets
;; it so cursor counters and stash contents don't bleed across tests.
(use-fixtures :each
  {:before (fn [] (wire/reset-store!))})

;; ---------------------------------------------------------------------------
;; Trivial fast path — values pass through bare, no cursor allocation.
;; ---------------------------------------------------------------------------

(deftest trivial-scalars-pass-through-bare
  (testing "scalars are wire-safe by definition; no cursor, no walk"
    (is (= nil (wire/return! nil)))
    (is (= 42 (wire/return! 42)))
    (is (= true (wire/return! true)))
    (is (= :a-keyword (wire/return! :a-keyword)))
    (is (= 'a-symbol (wire/return! 'a-symbol)))
    (is (= "short string" (wire/return! "short string")))))

(deftest trivial-small-collections-pass-through-bare
  (testing "small collections of scalars ship as-is"
    (is (= [:a :b :c] (wire/return! [:a :b :c])))
    (is (= {:counter 0 :items []} (wire/return! {:counter 0 :items []})))
    (is (= #{1 2 3} (wire/return! #{1 2 3})))))

(deftest trivial-empty-collections-always-pass-through
  (is (= [] (wire/return! [])))
  (is (= {} (wire/return! {})))
  (is (= #{} (wire/return! #{}))))

(deftest trivial-fast-path-allocates-no-cursor
  (testing "fast-path values do not increment the cursor counter"
    (let [before (:counter (wire/store-stats))]
      (dotimes [_ 10]
        (wire/return! {:small :map}))
      (is (= before (:counter (wire/store-stats)))))))

;; ---------------------------------------------------------------------------
;; Non-trivial wrapping — wire shape with cursor + (maybe) elisions.
;; ---------------------------------------------------------------------------

(deftest non-trivial-gets-cursor-and-wire-shape
  (let [v      (vec (range 200))
        result (wire/return! v)]
    (testing "result is a map with the wire-shape sentinel keys"
      (is (map? result))
      (is (string? (:rfp.wire/cursor result)))
      (is (contains? result :rfp.wire/value)))
    (testing "fits-in-budget result has no elisions key"
      ;; 200 ints are well under 256KB
      (is (not (contains? result :rfp.wire/elisions))))))

(deftest cursor-format-includes-session-id
  (let [v (vec (range 200))
        {cursor :rfp.wire/cursor} (wire/return! v)]
    (is (re-matches #".+/\d+" cursor)
        "cursor format is <session-id>/<n>")))

(deftest stash-survives-and-fetches-original
  (let [v {:counter 0 :items (vec (range 500))}
        {cursor :rfp.wire/cursor} (wire/return! v)]
    (testing "fetch-cursor returns the full original value"
      (is (= v (wire/fetch-cursor cursor))))
    (testing "fetch-path drills into the original"
      (is (= 0 (wire/fetch-path cursor [:counter])))
      (is (= 250 (wire/fetch-path cursor [:items 250]))))))

;; ---------------------------------------------------------------------------
;; Elision — oversized branches replaced with structured markers.
;; ---------------------------------------------------------------------------

(deftest large-string-elided-with-summary
  (let [big-str (apply str (repeat 100000 \X))
        v       {:big big-str :small "ok"}
        result  (wire/return! v)
        elisions (:rfp.wire/elisions result)]
    (testing "the oversized branch shows up in :rfp.wire/elisions"
      (is (= 1 (count elisions)))
      (let [el (first elisions)]
        (is (= [:big] (:path el)))
        (is (= :branch-too-big (:reason el)))
        (is (= :string (-> el :summary :type)))
        (is (= 100000 (-> el :summary :length)))
        (is (string? (-> el :summary :preview)))))
    (testing ":small remains inline at its real value"
      (is (= "ok" (get-in result [:rfp.wire/value :small]))))
    (testing "the elided path is recoverable via the cursor"
      (is (= big-str
             (wire/fetch-path (:rfp.wire/cursor result) [:big]))))))

(deftest map-summary-includes-keys-and-count
  (let [big-map  (zipmap (map #(keyword (str "k" %)) (range 1000))
                         (map #(apply str (repeat 200 \Y)) (range 1000)))
        v        {:huge big-map}
        result   (wire/return! v)
        elision  (first (:rfp.wire/elisions result))]
    (is (= [:huge] (:path elision)))
    (is (= :map (-> elision :summary :type)))
    (is (= 1000 (-> elision :summary :count)))
    (is (= 8 (count (-> elision :summary :sample-keys))))))

(deftest budget-exhaustion-elides-late-branches
  (testing "with a tight total budget but a permissive per-branch cap,
            the root map descends and late children get budget-exhausted"
    (let [v       (zipmap (range 100)
                          (map #(vec (repeat 100 (str "padding-" %))) (range 100)))
          ;; branch-bytes large enough that the whole map's estimate
          ;; doesn't trigger whole-coll elision; budget-bytes tight
          ;; enough that descent runs out partway.
          result  (wire/return! v {:budget-bytes 10000 :branch-bytes 1000000})]
      (is (some? (:rfp.wire/elisions result)))
      (is (some #(= :budget-exhausted (:reason %))
                (:rfp.wire/elisions result))))))

(deftest non-edn-leaves-stringified
  (testing "fns / atoms / volatiles get stringified so the wire stays EDN-readable"
    (let [v       {:f inc :tail (vec (range 200))}
          result  (wire/return! v)]
      (is (string? (get-in result [:rfp.wire/value :f]))
          ":f should be stringified (was a fn)"))))

(deftest wire-shape-survives-edn-roundtrip
  (testing "the entire wire response can pr-str then read-string without loss"
    (let [v      {:nested (vec (repeat 1000 :keyword))
                  :big    (apply str (repeat 5000 \Q))}
          result (wire/return! v)
          round  (reader/read-string (pr-str result))]
      (is (= result round)))))

;; ---------------------------------------------------------------------------
;; The no-data-loss invariant — every elision is reachable from the stash.
;; ---------------------------------------------------------------------------

(deftest every-elision-resolves-via-cursor-and-path
  (testing "for every elision marker the wrapper emits, the original
            value at that path must be retrievable from the stash"
    (let [v       {:tag    :report-loaded
                   :result (zipmap (range 200) (range 200))
                   :nested {:deep (vec (range 1000))}}
          result  (wire/return! v {:budget-bytes 1024 :branch-bytes 512})
          cursor  (:rfp.wire/cursor result)]
      (doseq [el (:rfp.wire/elisions result)]
        (is (some? (wire/fetch-path cursor (:path el)))
            (str "Elided path " (pr-str (:path el))
                 " must resolve in the stash"))))))

;; ---------------------------------------------------------------------------
;; Result-store mechanics
;; ---------------------------------------------------------------------------

(deftest store-is-bounded-and-evicts-oldest
  (testing "with a tiny max-size, oldest cursors get evicted"
    ;; Tweaking max-size via the store directly — there's no public knob
    (swap! @#'wire/result-store assoc :max-size 3)
    (let [c1 (:rfp.wire/cursor (wire/return! (vec (range 100))))
          c2 (:rfp.wire/cursor (wire/return! (vec (range 100))))
          c3 (:rfp.wire/cursor (wire/return! (vec (range 100))))
          c4 (:rfp.wire/cursor (wire/return! (vec (range 100))))]
      (is (nil? (wire/fetch-cursor c1)) "oldest evicted")
      (is (some? (wire/fetch-cursor c2)))
      (is (some? (wire/fetch-cursor c3)))
      (is (some? (wire/fetch-cursor c4))))))

(deftest release-removes-from-store
  (let [v       (vec (range 200))
        cursor  (:rfp.wire/cursor (wire/return! v))]
    (is (= v (wire/fetch-cursor cursor)))
    (wire/release-cursor! cursor)
    (is (nil? (wire/fetch-cursor cursor)))))

(deftest cursor-monotonic-within-session
  (let [counters (->> (range 5)
                      (map (fn [_] (wire/return! (vec (range 100)))))
                      (map :rfp.wire/cursor)
                      (map #(js/parseInt (last (clojure.string/split % "/")))))]
    (is (apply < counters)
        "cursor counter strictly monotonic")))

;; ---------------------------------------------------------------------------
;; Property-style asserts (without test.check — handcrafted generative cases)
;; ---------------------------------------------------------------------------

(defn- shape-class [v]
  (cond (map? v) :map (vector? v) :vec (set? v) :set (string? v) :string :else :leaf))

(deftest synthetic-large-payloads-no-data-loss
  (testing "across a representative payload zoo, every elision recovers"
    (let [payloads
          [{:event [:report/loaded {:rows (vec (range 5000))}]}
           {:effects {:db (zipmap (range 500) (range 500))}}
           {:app-db/diff {:only-after (apply str (repeat 50000 \W))}}
           {:huge-vec (vec (repeat 10000 {:k 1 :v 2}))}
           (vec (repeat 1000 (vec (repeat 100 :pad))))
           {:nested {:deeply {:nested (vec (range 10000))}}}]]
      (doseq [v payloads]
        (let [result (wire/return! v)
              cursor (:rfp.wire/cursor result)]
          (when (seq (:rfp.wire/elisions result))
            (doseq [el (:rfp.wire/elisions result)]
              (is (some? (wire/fetch-path cursor (:path el)))
                  (str "Elided path " (pr-str (:path el))
                       " in payload " (shape-class v)
                       " must resolve")))))))))

(deftest wire-response-bounded-by-budget
  (testing "the printed wire response stays under (roughly) the budget"
    (let [budget  16384
          big     (zipmap (range 1000)
                          (map #(apply str (repeat 1000 (char (+ 65 (mod % 26))))) (range 1000)))
          result  (wire/return! big {:budget-bytes budget :branch-bytes 8192})
          printed (pr-str result)]
      ;; printed contains the cursor-bearing wrapper, the elided sub-shape,
      ;; and the elision metadata — should be tens of KB at most, far under
      ;; shadow-cljs's 1MB cap. We assert against a generous multiple of
      ;; budget to stay tolerant of representation overhead.
      (is (< (count printed) (* 4 budget))
          (str "wire response should fit in ~"
               (* 4 budget) " bytes; got " (count printed))))))

(deftest fast-path-and-slow-path-are-equal-when-fits
  (testing "a non-trivial value that fits in budget produces a wire shape
            whose :rfp.wire/value reconstructs to the original"
    (let [v       (vec (range 200))
          result  (wire/return! v)]
      (is (= v (:rfp.wire/value result))))))
