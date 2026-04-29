#!/usr/bin/env bb
;;;; tests/ops_smoke.bb — babashka-side tests for scripts/ops.clj.
;;;;
;;;; Why this file exists
;;;; ---------------------
;;;; `npm test` only compiles + runs the CLJS unit-test build (the
;;;; tests/runtime/* deftests). It never loads scripts/ops.clj. The
;;;; rfp-r5s D regression (rfp-xhx) — a forward reference to
;;;; list-builds-on-port that broke `bb scripts/ops.clj` for every
;;;; subcommand — slipped past `npm test` for exactly that reason.
;;;;
;;;; This runner closes that gap. It exercises:
;;;;   1. The babashka load path: ops.clj parses + dispatches. A
;;;;      forward-reference, missing require, or other analysis-time
;;;;      error here would have caught rfp-xhx the first time.
;;;;   2. Pure helpers (list-builds-on-port, read-port-candidates)
;;;;      via with-redefs over the nREPL / fs seams.
;;;;
;;;; Run via `npm run test:ops` (see package.json) or directly:
;;;;   OPS_NO_AUTO_RUN=1 bb tests/ops_smoke.bb
;;;;
;;;; OPS_NO_AUTO_RUN=1 is required so ops.clj's main dispatcher does
;;;; not fire when load-file'd below; npm test:ops sets it via the
;;;; npm-script env, but a direct bb invocation must set it inline.

(require '[clojure.test :refer [deftest is testing run-tests]]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '(java.io File))

;; Load ops.clj as a library; package.json sets OPS_NO_AUTO_RUN in the
;; test environment so the script dispatcher does not auto-run.
(spit "/dev/null" "") ;; touch fs to ensure cwd is sane
(load-file (str (System/getProperty "user.dir") "/scripts/ops.clj"))

;; ---------------------------------------------------------------------------
;; Tests for rfp-r5s D: multi-build awareness in discover-app.sh
;; ---------------------------------------------------------------------------

(deftest list-builds-on-port-vec-input
  (testing "shadow returns a vec — list-builds-on-port returns it as-is"
    (with-redefs [ops/nrepl-eval-raw  (fn [_ _] :unused)
                  ops/combine-responses (fn [_] {:value "[:app :storybook]"})]
      (is (= [:app :storybook] (#'ops/list-builds-on-port 8777))))))

(deftest list-builds-on-port-empty-collection
  (testing "shadow returns [] — list-builds-on-port returns an empty vec"
    (with-redefs [ops/nrepl-eval-raw  (fn [_ _] :unused)
                  ops/combine-responses (fn [_] {:value "[]"})]
      (is (= [] (#'ops/list-builds-on-port 8777))))))

(deftest list-builds-on-port-nil-value
  (testing "shadow returns no value (nREPL :value nil) — returns nil"
    (with-redefs [ops/nrepl-eval-raw  (fn [_ _] :unused)
                  ops/combine-responses (fn [_] {:value nil})]
      (is (nil? (#'ops/list-builds-on-port 8777))))))

(deftest list-builds-on-port-exception-path
  (testing "nrepl-eval-raw throws — list-builds-on-port returns nil, not bubbles up"
    (with-redefs [ops/nrepl-eval-raw (fn [_ _] (throw (ex-info "no socket" {})))]
      (is (nil? (#'ops/list-builds-on-port 8777))))))

(deftest list-builds-on-port-set-input
  (testing "shadow-cljs returns a set (e.g. #{:app}) — coerced to a sorted vec"
    (with-redefs [ops/nrepl-eval-raw  (fn [_ _] :unused)
                  ops/combine-responses (fn [_] {:value "#{:app}"})]
      (is (= [:app] (#'ops/list-builds-on-port 8777))))

    (with-redefs [ops/nrepl-eval-raw  (fn [_ _] :unused)
                  ops/combine-responses (fn [_] {:value "#{:storybook :app}"})]
      ;; Sets are unordered — the fn sorts by str so the result is
      ;; deterministic across calls.
      (is (= [:app :storybook] (#'ops/list-builds-on-port 8777))))))

(deftest read-port-candidates-returns-vec-shape
  (testing "read-port-candidates returns a vec regardless of env state"
    (with-redefs [ops/port-file-candidates ["nope/.port"]]
      ;; Mocking SHADOW_CLJS_NREPL_PORT from a JVM is impractical
      ;; (System/setenv doesn't exist; reflection-based hacks are
      ;; flaky across Babashka builds). The env-set branch is left
      ;; for a future rfp bead — see read-port-candidates-file-cascade
      ;; below for file-mode coverage. This deftest only pins the
      ;; return-shape contract: a vec, never nil.
      (let [orig (System/getenv "SHADOW_CLJS_NREPL_PORT")]
        (try
          (is (vector? (#'ops/read-port-candidates))
              "read-port-candidates returns a vec regardless of env state")
          (finally nil))))))

(deftest read-port-candidates-file-cascade
  (testing "candidate files in order; existing files become :port + :path"
    (let [tmp-dir (System/getProperty "java.io.tmpdir")
          a       (str tmp-dir "/rfp-test-a.port")
          b       (str tmp-dir "/rfp-test-b.port")]
      (try
        (spit a "8777")
        (spit b "9999")
        (with-redefs [ops/port-file-candidates [a b]]
          (let [r (#'ops/read-port-candidates)]
            (is (= 2 (count r)))
            (is (= 8777 (:port (first r))))
            (is (= 9999 (:port (second r))))
            (is (= a    (:path (first r))))))
        (finally
          (.delete (io/file a))
          (.delete (io/file b)))))))

(deftest read-port-candidates-dedupes-by-port
  (testing "two candidate paths pointing at the same port are deduped"
    (let [tmp-dir (System/getProperty "java.io.tmpdir")
          a       (str tmp-dir "/rfp-test-dup-a.port")
          b       (str tmp-dir "/rfp-test-dup-b.port")]
      (try
        (spit a "8777")
        (spit b "8777") ;; same port, two paths
        (with-redefs [ops/port-file-candidates [a b]]
          (let [r (#'ops/read-port-candidates)]
            (is (= 1 (count r)) "duplicate ports collapsed to one entry")
            (is (= 8777 (:port (first r))))
            ;; First path wins (cascade order preserved).
            (is (= a (:path (first r))))))
        (finally
          (.delete (io/file a))
          (.delete (io/file b)))))))

(deftest read-port-candidates-skips-non-numeric
  (testing "candidate file with garbage contents is skipped, not crashed-on"
    (let [tmp-dir (System/getProperty "java.io.tmpdir")
          good    (str tmp-dir "/rfp-test-good.port")
          bad     (str tmp-dir "/rfp-test-bad.port")]
      (try
        (spit good "8777")
        (spit bad  "not-a-number")
        (with-redefs [ops/port-file-candidates [bad good]]
          (let [r (#'ops/read-port-candidates)]
            (is (= 1 (count r)))
            (is (= 8777 (:port (first r))))))
        (finally
          (.delete (io/file good))
          (.delete (io/file bad)))))))

;; The big one — would have caught rfp-xhx. If ops.clj has a
;; load-time error (forward reference, missing require, etc.),
;; the (load-file ...) at the top of THIS file throws and no test
;; ever runs. The CI smoke step also runs `bb scripts/ops.clj
;; unknown-subcommand` for the same reason; this in-repo test runs
;; the SAME load locally so devs catch regressions before pushing.
(deftest ops-clj-loaded-cleanly
  (testing "ops.clj loaded; helpers and dispatcher are reachable"
    (is (var? #'ops/-main))
    (is (var? #'ops/list-builds-on-port))
    (is (var? #'ops/read-port-candidates))
    (is (var? #'ops/discover-list))))

;; ---------------------------------------------------------------------------
;; rfp-jfp: build-id-from-args accepts both --build=app and --build=:app
;; ---------------------------------------------------------------------------
;;
;; (keyword ":app") produces a keyword whose name is the literal string
;; ":app", NOT the regular :app keyword. Without stripping the leading
;; colon before keyword, --build=:app silently mis-selected the build —
;; downstream cljs-eval got an unmatched build-id and inject failed
;; silently. discover-app.sh:6 documents the leading-colon form, so
;; this had been broken for as long as the flag has existed.

(deftest build-id-from-args-bare-form
  (testing "--build=app yields :app"
    (is (= :app (#'ops/build-id-from-args ["--build=app"])))
    (is (= :storybook (#'ops/build-id-from-args ["--build=storybook"])))))

(deftest build-id-from-args-colon-form
  (testing "--build=:app yields :app (not the malformed ::app)"
    (is (= :app (#'ops/build-id-from-args ["--build=:app"])))
    (is (= :storybook (#'ops/build-id-from-args ["--build=:storybook"])))
    ;; The pre-fix behaviour produced (keyword \":app\") whose name is
    ;; the literal \":app\" string. Make sure we're NOT in that state.
    (is (not= ":app" (name (#'ops/build-id-from-args ["--build=:app"]))))
    (is (= "app"     (name (#'ops/build-id-from-args ["--build=:app"]))))))

(deftest build-id-from-args-default
  (testing "no --build= arg → default-build-id"
    (is (= ops/default-build-id (#'ops/build-id-from-args [])))
    (is (= ops/default-build-id (#'ops/build-id-from-args ["--something-else"])))))

(deftest build-id-from-args-amongst-other-args
  (testing "--build= is picked out from a longer arg list"
    (is (= :app (#'ops/build-id-from-args ["[:event/foo]" "--build=:app" "--sync"])))
    (is (= :app (#'ops/build-id-from-args ["--sync" "--build=app" "[:foo]"])))))

(deftest discover-no-capture-passes-health-option
  (testing "--no-capture asks runtime health to skip console/native trace installs"
    (let [seen    (atom nil)
          emitted (atom nil)]
      (with-redefs [ops/ensure-port!         (fn [] true)
                    ops/inject-runtime!      (fn [_build-id opts]
                                               (reset! seen opts)
                                               {:ok? true
                                                :ten-x-loaded? true
                                                :trace-enabled? true
                                                :re-com-debug? true
                                                :versions {:by-dep {}}})
                    ops/startup-context      (fn [_build-id]
                                               {:app-db {} :recent-events []})
                    ops/read-port            (fn [] 8777)
                    ops/list-builds-on-port  (fn [_] [:app])
                    ops/emit                 (fn [m] (reset! emitted m))]
        (#'ops/discover ["--no-capture"])
        (is (= {:capture? false} @seen))
        (is (true? (:capture-skipped? @emitted)))))))

(deftest discover-emits-startup-context
  (testing "successful discover includes current app-db + recent event orientation"
    (let [emitted (atom nil)]
      (with-redefs [ops/ensure-port!         (fn [] true)
                    ops/inject-runtime!      (fn [_build-id _opts]
                                               {:ok? true
                                                :ten-x-loaded? true
                                                :trace-enabled? true
                                                :re-com-debug? true
                                                :versions {:by-dep {}}})
                    ops/startup-context      (fn [build-id]
                                               {:app-db {:screen :checkout}
                                                :recent-events [{:id 1 :event [:initialize]}]
                                                :build-id build-id})
                    ops/read-port            (fn [] 8777)
                    ops/list-builds-on-port  (fn [_] [:app])
                    ops/emit                 (fn [m] (reset! emitted m))]
        (#'ops/discover [])
        (is (= {:app-db {:screen :checkout}
                :recent-events [{:id 1 :event [:initialize]}]
                :build-id :app}
               (:startup-context @emitted)))))))

;; ---------------------------------------------------------------------------
;; rfp-zml: --stub flag parsing for dispatch-with bridge
;; ---------------------------------------------------------------------------

(deftest collect-flag-values-multiple
  (testing "every value following an exact `--stub` token comes back"
    (is (= [":http-xhrio" ":navigate"]
           (#'ops/collect-flag-values
            ["--stub" ":http-xhrio" "--stub" ":navigate" "[:ev]"]
            "--stub")))))

(deftest collect-flag-values-empty
  (testing "no flags present → empty vec"
    (is (= [] (#'ops/collect-flag-values ["[:ev]" "--trace"] "--stub")))))

(deftest collect-flag-values-trailing-bare-flag
  (testing "trailing `--stub` with no following value is silently dropped
            (forgiving — surfaces nothing rather than crashing on
             malformed input)"
    (is (= [":http-xhrio"]
           (#'ops/collect-flag-values
            ["--stub" ":http-xhrio" "--stub"]
            "--stub")))))

(deftest parse-stub-fx-ids-strips-leading-colon
  (testing "operator-typed `--stub :http-xhrio` and `--stub http-xhrio`
            both yield the keyword :http-xhrio (matches build-id-from-args
            keyword normalisation)"
    (is (= [:http-xhrio :navigate]
           (#'ops/parse-stub-fx-ids
            ["--stub" ":http-xhrio" "--stub" "navigate" "[:ev]"])))))

(deftest parse-stub-fx-ids-namespaced
  (testing "namespaced fx-ids parse to namespaced keywords"
    (is (= [:user/login-fx]
           (#'ops/parse-stub-fx-ids ["--stub" ":user/login-fx"])))))

(deftest parse-stub-fx-ids-empty
  (testing "no --stub flags → empty vec"
    (is (= [] (#'ops/parse-stub-fx-ids ["[:ev]" "--trace"])))))

;; ---------------------------------------------------------------------------
;; parse-predicate-args — watch-epochs.sh predicate map builder.
;; ---------------------------------------------------------------------------

(deftest parse-predicate-args-each-flag
  (testing "individual watch predicate flags produce the runtime map shape"
    (is (= {:event-id :cart/add}
           (#'ops/parse-predicate-args ["--event-id" ":cart/add"])))
    (is (= {:event-id-prefix ":cart/"}
           (#'ops/parse-predicate-args ["--event-id-prefix" "cart/"])))
    (is (= {:event-id-prefix ":cart/"}
           (#'ops/parse-predicate-args ["--event-id-prefix" ":cart/"])))
    (is (= {:effects :http-xhrio}
           (#'ops/parse-predicate-args ["--effects" "http-xhrio"])))
    (is (= {:timing-ms [:> 100]}
           (#'ops/parse-predicate-args ["--timing-ms" ">100"])))
    (is (= {:timing-ms [:< 5]}
           (#'ops/parse-predicate-args ["--timing-ms" "<5"])))
    (is (= {:touches-path [:cart :items]}
           (#'ops/parse-predicate-args ["--touches-path" "[:cart :items]"])))
    (is (= {:sub-ran :cart/total}
           (#'ops/parse-predicate-args ["--sub-ran" "cart/total"])))
    (is (= {:render "re-com.buttons/button"}
           (#'ops/parse-predicate-args ["--render" "re-com.buttons/button"])))))

(deftest parse-predicate-args-combines-flags
  (testing "flags compose into one predicate map; later duplicate flags win"
    (is (= {:event-id-prefix ":cart/"
            :effects :dispatch
            :timing-ms [:> 25]
            :sub-ran :cart/total}
           (#'ops/parse-predicate-args
            ["--event-id-prefix" "cart/"
             "--effects" ":dispatch"
             "--timing-ms" ">25"
             "--sub-ran" ":cart/total"])))
    (is (= {:render "new"}
           (#'ops/parse-predicate-args ["--render" "old" "--render" "new"])))))

(deftest parse-predicate-args-rejects-bad-timing-and-custom
  (testing "bad timing syntax and deferred custom predicate flag die cleanly"
    (with-redefs [ops/die (fn [reason & {:as data}]
                            (throw (ex-info "die" (assoc data :reason reason))))]
      (is (= :bad-timing-ms
             (try (#'ops/parse-predicate-args ["--timing-ms" ">=100"])
                  (catch clojure.lang.ExceptionInfo e
                    (:reason (ex-data e))))))
      (is (= :bad-timing-ms
             (try (#'ops/parse-predicate-args ["--timing-ms" "><100"])
                  (catch clojure.lang.ExceptionInfo e
                    (:reason (ex-data e))))))
      (is (= :flag-not-supported
             (try (#'ops/parse-predicate-args ["--custom" "(fn [_] true)"])
                  (catch clojure.lang.ExceptionInfo e
                    (:reason (ex-data e)))))))))

;; ---------------------------------------------------------------------------
;; partition-dispatch-args — order-independent split of dispatch.sh args.
;;
;; The docstring promises three invocations parse identically:
;;   dispatch.sh '[:ev]' --trace
;;   dispatch.sh --trace '[:ev]'
;;   dispatch.sh --trace '[:ev]' --build=app
;; Pin those plus a few edge cases so a regression that drops --build= when
;; it appears before the event-vec breaks the test, not the live shim.
;; ---------------------------------------------------------------------------

(deftest partition-dispatch-args-event-only
  (testing "single edn vec, no flags"
    (is (= ["[:ev]" '()]
           (#'ops/partition-dispatch-args ["[:ev]"])))))

(deftest partition-dispatch-args-event-then-flags
  (testing "event-vec first, flags after — flags preserved"
    (let [[ev rest-args] (#'ops/partition-dispatch-args ["[:ev]" "--trace"])]
      (is (= "[:ev]" ev))
      (is (= ["--trace"] (vec rest-args))))))

(deftest partition-dispatch-args-flags-then-event
  (testing "flags before event-vec — order-independent extraction"
    (let [[ev rest-args] (#'ops/partition-dispatch-args ["--trace" "[:ev]"])]
      (is (= "[:ev]" ev))
      (is (= ["--trace"] (vec rest-args))))))

(deftest partition-dispatch-args-flags-around-event
  (testing "flags both before AND after event-vec — both preserved"
    (let [[ev rest-args] (#'ops/partition-dispatch-args
                          ["--trace" "[:ev :foo]" "--build=app"])]
      (is (= "[:ev :foo]" ev))
      (is (= 2 (count rest-args)))
      (is (some #{"--trace"} rest-args))
      (is (some #{"--build=app"} rest-args)))))

(deftest partition-dispatch-args-no-event
  (testing "no edn vec at all — event is nil; flags still surface"
    (let [[ev rest-args] (#'ops/partition-dispatch-args ["--trace" "--build=app"])]
      (is (nil? ev))
      (is (= ["--trace" "--build=app"] (vec rest-args))))))

(deftest partition-dispatch-args-multiple-edn-vecs
  (testing "multiple edn-vec args — first is the event; subsequent vecs
            are surfaced as 'other args' so the caller can decide. The
            current behaviour is documented (group-by + first), and we
            pin it so a refactor doesn't silently merge or drop them."
    (let [[ev rest-args] (#'ops/partition-dispatch-args
                          ["[:ev1]" "[:ev2]" "--flag"])]
      (is (= "[:ev1]" ev))
      ;; rest-args = others ++ (rest events) = ["--flag" "[:ev2]"]
      (is (some #{"[:ev2]"} rest-args))
      (is (some #{"--flag"} rest-args)))))

;; ---------------------------------------------------------------------------
;; bencode / bdecode — hand-rolled nREPL transport. Static-fixture roundtrip
;; covers the four bencode shapes (int, string, list, dict) plus the empty
;; collections and a string with a colon (the bencode delimiter).
;; ---------------------------------------------------------------------------

(defn- bencode-roundtrip
  "Encode v with bencode, then decode the bytes via bdecode. Returns
   the decoded value. Helper for the deftests below."
  [v]
  (let [encoded (#'ops/bencode v)
        in      (java.io.PushbackInputStream.
                  (java.io.ByteArrayInputStream. (.getBytes encoded "UTF-8")))]
    (#'ops/bdecode in)))

(deftest bencode-bdecode-roundtrip-integer
  (is (= 0     (bencode-roundtrip 0)))
  (is (= 42    (bencode-roundtrip 42)))
  (is (= -1    (bencode-roundtrip -1)))
  (is (= 99999 (bencode-roundtrip 99999))))

(deftest bencode-bdecode-roundtrip-string
  (is (= ""               (bencode-roundtrip "")))
  (is (= "hello"          (bencode-roundtrip "hello")))
  (is (= "with spaces"    (bencode-roundtrip "with spaces")))
  (is (= "has:a:colon"    (bencode-roundtrip "has:a:colon")))
  (is (= "newline\n here" (bencode-roundtrip "newline\n here"))))

(deftest bencode-bdecode-roundtrip-list
  (is (= []          (bencode-roundtrip [])))
  (is (= ["a" "b"]   (bencode-roundtrip ["a" "b"])))
  (is (= [1 "two" 3] (bencode-roundtrip [1 "two" 3]))))

(deftest bencode-bdecode-roundtrip-dict
  (testing "bencode keys are sorted on encode; round-trip preserves shape
            but the keys come back as strings (bencode has no kw type)"
    (is (= {} (bencode-roundtrip {})))
    (is (= {"k" "v"} (bencode-roundtrip {"k" "v"})))
    (is (= {"a" 1 "b" 2} (bencode-roundtrip {"a" 1 "b" 2})))
    ;; keyword keys round-trip to strings (one-way demunge)
    (is (= {"op" "eval" "code" "(+ 1 2)"}
           (bencode-roundtrip {:op "eval" :code "(+ 1 2)"})))))

(deftest bencode-bdecode-roundtrip-nested
  (testing "lists of dicts and dicts of lists round-trip cleanly"
    (let [v {"items" [{"id" 1 "name" "a"} {"id" 2 "name" "b"}]
             "count" 2}]
      (is (= v (bencode-roundtrip v))))))

;; ---------------------------------------------------------------------------
;; form-end-offsets / chunk-source — runtime.cljs splitter for the inject path.
;;
;; runtime.cljs is ~85KB; the nREPL transport ceiling is ~64KB. chunked-inject!
;; splits at top-level form boundaries, re-asserting the ns at the head of
;; chunks 2+. A bug here means every shim breaks silently — pin the contract
;; with static fixtures.
;; ---------------------------------------------------------------------------

(deftest form-end-offsets-three-forms
  (testing "returns end-offset for each top-level form, in order"
    (let [src "(ns demo)\n(def a 1)\n(def b 2)"
          offs (#'ops/form-end-offsets src)]
      (is (= 3 (count offs))
          "one offset per top-level form")
      ;; First form ends at index 9 (after "(ns demo)").
      (is (= 9 (nth offs 0)))
      ;; Second form: "(ns demo)\n(def a 1)" → 19.
      (is (= 19 (nth offs 1)))
      ;; Third form: full source length (no trailing newline) = 29.
      (is (= 29 (count src)))
      (is (= 29 (nth offs 2))))))

(deftest form-end-offsets-handles-tagged-literals
  (testing "tools.reader data-readers shim handles CLJS tags
            (#js, #queue, #inst, #uuid) without throwing"
    (let [src "(ns demo)\n(def x #js {:k 1})\n(def y 2)"]
      (is (= 3 (count (#'ops/form-end-offsets src)))
          "must read all three forms, including the #js literal in form 2"))))

(deftest form-end-offsets-handles-comments
  (testing "comments and whitespace between forms don't add false offsets"
    (let [src ";; preamble\n(ns demo)\n;; mid\n(def a 1)"]
      (is (= 2 (count (#'ops/form-end-offsets src)))
          "two top-level forms; the two comment lines are not forms"))))

(deftest chunk-source-single-chunk-fits-verbatim
  (testing "source under max-bytes returns 1 chunk, byte-for-byte verbatim"
    (let [src    "(ns demo)\n(def a 1)\n(def b 2)"
          chunks (#'ops/chunk-source src 10000)]
      (is (= 1 (count chunks)))
      (is (= src (first chunks))))))

(deftest chunk-source-multi-chunk-prepends-ns
  (testing "source over max-bytes splits into N chunks; chunks 2+ are
            prefixed with the ns form so cljs-eval lands in the right ns"
    (let [src    (str "(ns demo)\n"
                      "(def a 1)\n"
                      "(def b 2)\n"
                      "(def c 3)\n"
                      "(def d 4)")
          ;; Max-bytes small enough to force multiple chunks (~25 bytes
          ;; allows ns + ~1 form per chunk).
          chunks (#'ops/chunk-source src 25)]
      (is (>= (count chunks) 2)
          "the size pressure must produce at least 2 chunks")
      (is (clojure.string/starts-with? (first chunks) "(ns demo)")
          "chunk 1 starts with the ns form (verbatim leading slice)")
      (doseq [c (rest chunks)]
        (is (clojure.string/starts-with? c "(ns demo)\n")
            "chunks 2+ MUST re-assert the ns so the defns land in demo, not cljs.user")))))

(deftest chunk-source-each-chunk-under-budget
  (testing "every chunk fits under max-bytes (modulo the ns prefix overhead
            on chunks 2+, which the chunker accounts for)"
    (let [src     (apply str "(ns demo)\n"
                            (repeat 50 "(def x 1)\n"))
          max-b   200
          chunks  (#'ops/chunk-source src max-b)]
      (doseq [c chunks]
        (is (<= (count (.getBytes ^String c "UTF-8")) max-b)
            (str "chunk byte-size " (count (.getBytes ^String c "UTF-8"))
                 " exceeds budget " max-b))))))

(deftest chunk-source-reassembles-to-original-content
  (testing "concatenating chunks (stripping the repeated ns prefix from
            chunks 2+) recovers the original source"
    (let [src    (apply str "(ns demo)\n"
                           (for [i (range 30)] (str "(def x" i " " i ")\n")))
          chunks (#'ops/chunk-source src 200)
          ns-len 10  ;; "(ns demo)\n"
          recombined (str (first chunks)
                          (clojure.string/join
                            ""
                            (for [c (rest chunks)]
                              (subs c ns-len))))]
      (is (= (clojure.string/trim src)
             (clojure.string/trim recombined))
          "modulo trailing-newline trim, recombined matches original"))))

;; ---------------------------------------------------------------------------
;; --trace legacy fallback honours --stub.
;;
;; When re-frame predates rf-4mr (commit f8f0f59 — dispatch-and-settle), the
;; --trace path falls back to tagged-dispatch-sync! + collect-after-dispatch.
;; Pre-fix that fallback ignored --stub entirely: the override never planted
;; on event meta, the real fx fired, and the response still claimed
;; :stubbed-fx-ids — silently breaking the safety guarantee. The fallback now
;; routes through dispatch-sync-with-stubs! when --stub is present, mirroring
;; the sync? branch.
;; ---------------------------------------------------------------------------

(deftest dispatch-trace-legacy-fallback-routes-stubs-through-with-stubs
  (testing "--trace + --stub on a re-frame that lacks dispatch-and-settle
            invokes dispatch-sync-with-stubs! (not plain tagged-dispatch-sync!)
            so the override map plants on event meta and :stubbed-fx-ids
            surfaces back to the agent"
    (let [eval-forms (atom [])
          emitted    (atom nil)]
      (with-redefs [ops/ensure-port!     (fn [] nil)
                    ops/ensure-injected! (fn [_] false)
                    ops/cljs-eval-value  (fn [_build-id form]
                                           (swap! eval-forms conj form)
                                           (cond
                                             (str/includes? form "dispatch-and-settle!")
                                             {:ok? false :reason :dispatch-and-settle-unavailable}

                                             (str/includes? form "dispatch-sync-with-stubs!")
                                             {:ok? true :event [:user/login] :dispatch-id "abc"
                                              :stubbed-fx-ids [:http-xhrio]}

                                             (str/includes? form "collect-after-dispatch")
                                             {:ok? true :dispatch-id "abc" :epoch-id 1}

                                             :else
                                             {:ok? true}))
                    ops/emit             (fn [m] (reset! emitted m))]
        (#'ops/dispatch-op ["[:user/login]" "--trace" "--stub" ":http-xhrio"])
        (is (some #(str/includes? % "dispatch-sync-with-stubs!") @eval-forms)
            "legacy --trace must invoke dispatch-sync-with-stubs! when --stub supplied")
        (is (not-any? #(re-find #"\(re-frame-pair\.runtime/tagged-dispatch-sync! " %)
                      @eval-forms)
            "legacy --trace must NOT invoke plain tagged-dispatch-sync! when --stub supplied")
        (is (= [:http-xhrio] (:stubbed-fx-ids @emitted))
            ":stubbed-fx-ids surfaces from dispatch-sync-with-stubs! through the merge")
        (is (true? (:legacy? @emitted))
            "legacy? flag still set so the agent sees which branch was taken")))))

(deftest dispatch-form-selects-stub-aware-runtime-fns
  (testing "dispatch form construction keeps mode and stub axes separate"
    (is (= "(re-frame-pair.runtime/tagged-dispatch-sync! [:ev])"
           (#'ops/dispatch-form :sync "[:ev]" [])))
    (is (= "(re-frame-pair.runtime/dispatch-sync-with-stubs! [:ev] [:http])"
           (#'ops/dispatch-form :sync "[:ev]" [:http])))
    (is (= "(re-frame-pair.runtime/tagged-dispatch-sync! [:ev])"
           (#'ops/dispatch-form :trace-legacy "[:ev]" [])))
    (is (= "(re-frame-pair.runtime/dispatch-sync-with-stubs! [:ev] [:http])"
           (#'ops/dispatch-form :trace-legacy "[:ev]" [:http])))
    (is (= "(re-frame-pair.runtime/tagged-dispatch! [:ev])"
           (#'ops/dispatch-form :queued "[:ev]" [])))
    (is (= "(re-frame-pair.runtime/dispatch-with-stubs! [:ev] [:http])"
           (#'ops/dispatch-form :queued "[:ev]" [:http])))))

(deftest dispatch-trace-legacy-fallback-no-stubs-still-uses-tagged-dispatch-sync
  (testing "--trace without --stub on a legacy re-frame must keep using
            tagged-dispatch-sync! (regression guard for the no-stub path)"
    (let [eval-forms (atom [])]
      (with-redefs [ops/ensure-port!     (fn [] nil)
                    ops/ensure-injected! (fn [_] false)
                    ops/cljs-eval-value  (fn [_build-id form]
                                           (swap! eval-forms conj form)
                                           (cond
                                             (str/includes? form "dispatch-and-settle!")
                                             {:ok? false :reason :dispatch-and-settle-unavailable}

                                             (str/includes? form "tagged-dispatch-sync!")
                                             {:ok? true :event [:user/login] :dispatch-id "abc"}

                                             (str/includes? form "collect-after-dispatch")
                                             {:ok? true :dispatch-id "abc" :epoch-id 1}

                                             :else
                                             {:ok? true}))
                    ops/emit             (fn [_] nil)]
        (#'ops/dispatch-op ["[:user/login]" "--trace"])
        (is (some #(re-find #"\(re-frame-pair\.runtime/tagged-dispatch-sync! " %)
                  @eval-forms)
            "no --stub: legacy path still uses tagged-dispatch-sync!")
        (is (not-any? #(str/includes? % "dispatch-sync-with-stubs!") @eval-forms)
            "no --stub: dispatch-sync-with-stubs! must NOT be invoked")))))

(deftest dispatch-trace-legacy-fallback-rf-ge8-also-unavailable-surfaces-error
  (testing "--trace + --stub when re-frame predates BOTH rf-4mr and rf-ge8
            surfaces :reason :dispatch-sync-with-unavailable to the agent
            rather than silently falling through to plain tagged-dispatch-sync!"
    (let [emitted (atom nil)]
      (with-redefs [ops/ensure-port!     (fn [] nil)
                    ops/ensure-injected! (fn [_] false)
                    ops/cljs-eval-value  (fn [_build-id form]
                                           (cond
                                             (str/includes? form "dispatch-and-settle!")
                                             {:ok? false :reason :dispatch-and-settle-unavailable}

                                             (str/includes? form "dispatch-sync-with-stubs!")
                                             {:ok? false :reason :dispatch-sync-with-unavailable
                                              :hint "re-frame predates rf-ge8 (commit 2651a30)"}

                                             :else
                                             {:ok? true}))
                    ops/emit             (fn [m] (reset! emitted m))]
        (#'ops/dispatch-op ["[:user/login]" "--trace" "--stub" ":http-xhrio"])
        (is (= :dispatch-sync-with-unavailable (:reason @emitted))
            "structured failure propagates rather than silent fall-through")
        (is (false? (:ok? @emitted))
            "ok? false preserved so the bash shim treats this as an error")
        (is (true? (:legacy? @emitted))
            "legacy? flag preserved on the failure path too")))))

;; ---------------------------------------------------------------------------
;; --sync and --queued (default) branches in dispatch-op.
;;
;; The pre-existing tests cover only the --trace branch (and its legacy
;; fallback). The other two branches in dispatch-op are equally load-bearing:
;;   --sync  — used by the SKILL.md *Recover from a click* and *Subscribe
;;             traceback* recipes.
;;   queued  — the default path (no flag) and the most common dispatch
;;             entry point.
;;
;; A regression that swapped the cljs-eval form selection (e.g. a queued
;; dispatch that silently routed through dispatch-sync-with-stubs!, or a
;; --stub on the queued path that fell through to plain tagged-dispatch!
;; and dropped the override) would silently de-sync caller assumptions.
;; These tests pin the branch selection and the {:mode :sync|:queued ...}
;; emit shape using the same with-redefs pattern as the --trace tests.
;; ---------------------------------------------------------------------------

(deftest dispatch-sync-routes-stubs-through-dispatch-sync-with-stubs
  (testing "--sync + --stub :http-xhrio invokes dispatch-sync-with-stubs!
            (and NOT plain tagged-dispatch-sync!), and emits {:mode :sync
            :stubbed-fx-ids [:http-xhrio] ...}"
    (let [eval-forms (atom [])
          emitted    (atom nil)]
      (with-redefs [ops/ensure-port!     (fn [] nil)
                    ops/ensure-injected! (fn [_] false)
                    ops/cljs-eval-value  (fn [_build-id form]
                                           (swap! eval-forms conj form)
                                           {:ok? true :event [:user/login] :dispatch-id "abc"
                                            :stubbed-fx-ids [:http-xhrio]})
                    ops/emit             (fn [m] (reset! emitted m))]
        (#'ops/dispatch-op ["[:user/login]" "--sync" "--stub" ":http-xhrio"])
        (is (some #(str/includes? % "dispatch-sync-with-stubs!") @eval-forms)
            "--sync + --stub must invoke dispatch-sync-with-stubs!")
        (is (not-any? #(re-find #"\(re-frame-pair\.runtime/tagged-dispatch-sync! " %)
                      @eval-forms)
            "--sync + --stub must NOT invoke plain tagged-dispatch-sync!")
        (is (= :sync (:mode @emitted))
            ":mode :sync stamped into the emit payload")
        (is (= [:http-xhrio] (:stubbed-fx-ids @emitted))
            ":stubbed-fx-ids surfaces from dispatch-sync-with-stubs! through the merge")))))

(deftest dispatch-sync-no-stubs-uses-tagged-dispatch-sync
  (testing "--sync without --stub invokes tagged-dispatch-sync! (and NOT
            dispatch-sync-with-stubs!), and emits {:mode :sync ...}"
    (let [eval-forms (atom [])
          emitted    (atom nil)]
      (with-redefs [ops/ensure-port!     (fn [] nil)
                    ops/ensure-injected! (fn [_] false)
                    ops/cljs-eval-value  (fn [_build-id form]
                                           (swap! eval-forms conj form)
                                           {:ok? true :event [:user/login] :dispatch-id "abc"})
                    ops/emit             (fn [m] (reset! emitted m))]
        (#'ops/dispatch-op ["[:user/login]" "--sync"])
        (is (some #(re-find #"\(re-frame-pair\.runtime/tagged-dispatch-sync! " %)
                  @eval-forms)
            "--sync without --stub must invoke tagged-dispatch-sync!")
        (is (not-any? #(str/includes? % "dispatch-sync-with-stubs!") @eval-forms)
            "--sync without --stub must NOT invoke dispatch-sync-with-stubs!")
        (is (= :sync (:mode @emitted))
            ":mode :sync stamped into the emit payload")))))

(deftest dispatch-queued-stubs-and-no-stubs
  (testing "queued + --stub :http-xhrio invokes dispatch-with-stubs! (the
            non-sync variant) and emits {:mode :queued :stubbed-fx-ids ...}"
    (let [eval-forms (atom [])
          emitted    (atom nil)]
      (with-redefs [ops/ensure-port!     (fn [] nil)
                    ops/ensure-injected! (fn [_] false)
                    ops/cljs-eval-value  (fn [_build-id form]
                                           (swap! eval-forms conj form)
                                           {:ok? true :event [:user/login] :dispatch-id "abc"
                                            :stubbed-fx-ids [:http-xhrio]})
                    ops/emit             (fn [m] (reset! emitted m))]
        (#'ops/dispatch-op ["[:user/login]" "--stub" ":http-xhrio"])
        (is (some #(re-find #"\(re-frame-pair\.runtime/dispatch-with-stubs! " %)
                  @eval-forms)
            "queued + --stub must invoke dispatch-with-stubs!")
        ;; dispatch-sync-with-stubs! contains the substring dispatch-with-stubs!,
        ;; so the assertion above can't use plain str/includes? — and likewise
        ;; we explicitly guard that we did NOT route through the sync variant.
        (is (not-any? #(str/includes? % "dispatch-sync-with-stubs!") @eval-forms)
            "queued + --stub must NOT invoke dispatch-sync-with-stubs!")
        (is (not-any? #(re-find #"\(re-frame-pair\.runtime/tagged-dispatch! " %)
                      @eval-forms)
            "queued + --stub must NOT invoke plain tagged-dispatch!")
        (is (= :queued (:mode @emitted))
            ":mode :queued stamped into the emit payload")
        (is (= [:http-xhrio] (:stubbed-fx-ids @emitted))
            ":stubbed-fx-ids surfaces from dispatch-with-stubs! through the merge"))))
  (testing "queued without --stub invokes tagged-dispatch! (and NOT
            dispatch-with-stubs! or any sync variant), and emits {:mode :queued ...}"
    (let [eval-forms (atom [])
          emitted    (atom nil)]
      (with-redefs [ops/ensure-port!     (fn [] nil)
                    ops/ensure-injected! (fn [_] false)
                    ops/cljs-eval-value  (fn [_build-id form]
                                           (swap! eval-forms conj form)
                                           {:ok? true :event [:user/login] :dispatch-id "abc"})
                    ops/emit             (fn [m] (reset! emitted m))]
        (#'ops/dispatch-op ["[:user/login]"])
        (is (some #(re-find #"\(re-frame-pair\.runtime/tagged-dispatch! " %)
                  @eval-forms)
            "queued without --stub must invoke tagged-dispatch!")
        (is (not-any? #(str/includes? % "dispatch-with-stubs!") @eval-forms)
            "queued without --stub must NOT invoke dispatch-with-stubs! (or its sync sibling)")
        (is (not-any? #(re-find #"\(re-frame-pair\.runtime/tagged-dispatch-sync! " %)
                      @eval-forms)
            "queued without --stub must NOT invoke tagged-dispatch-sync!")
        (is (= :queued (:mode @emitted))
            ":mode :queued stamped into the emit payload")))))

;; ---------------------------------------------------------------------------
;; Polling ops reuse one nREPL connection for repeated evals.
;; ---------------------------------------------------------------------------

(deftest await-settle-loop-reuses-single-nrepl-connection
  (testing "await-settle-loop opens one connection and reuses it across
            pending -> settled polls"
    (let [opened    (atom 0)
          eval-conns (atom [])
          responses (atom [{:pending? true}
                           {:settled? true :ok? true}])]
      (with-redefs [ops/settle-poll-ms              0
                    ops/with-current-nrepl-connection
                    (fn [f]
                      (f {:conn-id (swap! opened inc)}))
                    ops/cljs-eval-value
                    (fn
                      ([_build-id _form]
                       (throw (ex-info "non-persistent eval path used" {})))
                      ([conn _build-id _form]
                       (swap! eval-conns conj conn)
                       (let [r (first @responses)]
                         (swap! responses #(vec (rest %)))
                         r)))]
        (is (= {:settled? true :ok? true}
               (#'ops/await-settle-loop :app "handle-1")))
        (is (= 1 @opened))
        (is (= 2 (count @eval-conns)))
        (is (= 1 (count (distinct @eval-conns))))))))

(deftest await-settle-loop-times-out-while-pending
  (testing "pending responses past the wall-clock budget return :poll-timeout"
    (let [calls (atom 0)]
      (with-redefs [ops/settle-poll-ms        0
                    ops/settle-poll-budget-ms 0
                    ops/with-current-nrepl-connection
                    (fn [f] (f {:conn-id 1}))
                    ops/cljs-eval-value
                    (fn
                      ([_build-id _form]
                       (throw (ex-info "non-persistent eval path used" {})))
                      ([_conn _build-id _form]
                       (swap! calls inc)
                       {:pending? true}))]
        (is (= {:ok? false
                :reason :poll-timeout
                :handle "handle-1"
                :budget-ms 0}
               (#'ops/await-settle-loop :app "handle-1")))
        (is (= 1 @calls))))))

(deftest watch-op-reuses-single-nrepl-connection
  (testing "watch-op uses one persistent connection for the initial head
            lookup and subsequent poll eval"
    (let [opened     (atom 0)
          eval-conns (atom [])
          emitted    (atom [])]
      (with-redefs [ops/ensure-port!                 (fn [] true)
                    ops/ensure-injected!             (fn [_build-id] false)
                    ops/with-current-nrepl-connection
                    (fn [f]
                      (f {:conn-id (swap! opened inc)}))
                    ops/cljs-eval-value
                    (fn
                      ([_build-id _form]
                       (throw (ex-info "non-persistent eval path used" {})))
                      ([conn _build-id form]
                       (swap! eval-conns conj conn)
                       (if (= form "(re-frame-pair.runtime/latest-epoch-id)")
                         0
                         {:matches [{:event [:clicked]}]
                          :head-id 1})))
                    ops/emit                         (fn [m] (swap! emitted conj m))]
        (#'ops/watch-op ["--count" "1" "--poll-ms" "0" "--window-ms" "1000"])
        (is (= 1 @opened))
        (is (= 2 (count @eval-conns)))
        (is (= 1 (count (distinct @eval-conns))))
        (is (some :finished? @emitted))))))

(deftest tail-build-op-reuses-single-nrepl-connection
  (testing "tail-build-op uses one persistent connection for before/after
            probe evals"
    (let [opened     (atom 0)
          eval-conns (atom [])
          emitted    (atom nil)
          values     (atom [0 1])]
      (with-redefs [ops/ensure-port!                 (fn [] true)
                    ops/ensure-injected!             (fn [_build-id] false)
                    ops/tail-build-poll-ms           0
                    ops/with-current-nrepl-connection
                    (fn [f]
                      (f {:conn-id (swap! opened inc)}))
                    ops/cljs-eval-value
                    (fn
                      ([_build-id _form]
                       (throw (ex-info "non-persistent eval path used" {})))
                      ([conn _build-id _form]
                       (swap! eval-conns conj conn)
                       (let [v (first @values)]
                         (swap! values #(vec (rest %)))
                         v)))
                    ops/emit                         (fn [m] (reset! emitted m))]
        (#'ops/tail-build-op ["--probe" "(js/Date.now)" "--wait-ms" "1000"])
        (is (= 1 @opened))
        (is (= 2 (count @eval-conns)))
        (is (= 1 (count (distinct @eval-conns))))
        (is (= true (:ok? @emitted)))
        (is (= false (:soft? @emitted)))))))

;; ---------------------------------------------------------------------------
;; ensure-injected! follows up with a `health` call after a re-ship so the
;; native-epoch / native-trace cbs and console-capture wrapper are installed
;; in the freshly-shipped runtime. Without this, every op that hit the auto-
;; reinject path post-refresh saw empty native buffers and a missing console
;; wrapper — :reinjected? true was the only signal, and it's documented as
;; informational. The fast path (sentinel still present) must NOT invoke
;; health: it would burn an extra cljs-eval roundtrip per op for no gain.
;; ---------------------------------------------------------------------------

;; Helper: ensure-injected! slurps the runtime ns source via runtime-cljs-path
;; before delegating to chunked-inject!. The slurp uses *file* which doesn't
;; resolve to scripts/ during these tests. Each test below redefs
;; runtime-cljs-path to point at a known-readable file so slurp succeeds; the
;; mocked chunked-inject! ignores the contents anyway.
(def ^:private ^String readable-path
  (str (System/getProperty "user.dir") "/package.json"))

(deftest ensure-injected-installs-captures-after-reship
  (testing "on a fresh re-ship (sentinel missing post-refresh), ensure-injected!
            calls (re-frame-pair.runtime/health) so install-native-epoch-cb!,
            install-native-trace-cb!, install-console-capture!, and
            install-last-click-capture! actually run in the new runtime"
    (let [eval-calls (atom [])]
      (with-redefs [ops/runtime-already-injected? (fn [_build-id] false)
                    ops/runtime-cljs-path         (fn [] readable-path)
                    ops/chunked-inject!           (fn [_build-id _src] [[0 {:value "nil"}]])
                    ops/cljs-eval-value           (fn [_build-id form-str]
                                                    (swap! eval-calls conj form-str)
                                                    {:ok? true})]
        (let [reinjected? (#'ops/ensure-injected! :app)]
          (is (true? reinjected?)
              "fresh inject path returns true so callers can tag :reinjected?")
          (is (some #(str/includes? % "re-frame-pair.runtime/health") @eval-calls)
              "health must be called after a re-ship to wire up cbs"))))))

(deftest ensure-injected-skips-health-on-fast-path
  (testing "when the runtime sentinel is already present (no re-ship needed),
            ensure-injected! must NOT call health — the cbs from the prior
            inject are still wired, and an extra cljs-eval roundtrip per op
            would defeat the fast path"
    (let [eval-calls (atom [])]
      (with-redefs [ops/runtime-already-injected? (fn [_build-id] true)
                    ops/chunked-inject!           (fn [_ _]
                                                    (throw (ex-info "should not chunk-inject" {})))
                    ops/cljs-eval-value           (fn [_build-id form-str]
                                                    (swap! eval-calls conj form-str)
                                                    nil)]
        (let [reinjected? (#'ops/ensure-injected! :app)]
          (is (false? reinjected?) "sentinel present → no re-ship")
          (is (empty? @eval-calls)
              "fast path is silent — no health roundtrip"))))))

(deftest ensure-injected-skips-health-when-chunk-fails
  (testing "when chunked-inject! returns a failure (compile / transport), we
            die with :inject-failed BEFORE ever calling health — there's no
            point installing cbs into a partially-shipped ns"
    (let [eval-calls (atom [])]
      (with-redefs [ops/runtime-already-injected? (fn [_build-id] false)
                    ops/runtime-cljs-path         (fn [] readable-path)
                    ops/chunked-inject!           (fn [_build-id _src]
                                                    [[0 {:value "ok"}]
                                                     [1 {:reason :transport-error
                                                         :stage  :chunk-eval
                                                         :ex     "boom"
                                                         :err    "transport"
                                                         :hint   "test"
                                                         :chunk-count 2}]])
                    ops/cljs-eval-value           (fn [_build-id form-str]
                                                    (swap! eval-calls conj form-str)
                                                    {:ok? true})
                    ops/die                       (fn [reason & _]
                                                    (throw (ex-info "die" {:reason reason})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"die"
                              (#'ops/ensure-injected! :app))
            "die :inject-failed propagates")
        (is (empty? @eval-calls)
            "no health call on a failed inject — captures install nothing meaningful")))))

(let [{:keys [fail error]} (run-tests 'user)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
