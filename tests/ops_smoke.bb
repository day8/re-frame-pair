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

(require '[clojure.test :refer [deftest is testing run-tests use-fixtures]]
         '[clojure.java.io :as io]
         '[clojure.set]
         '[clojure.string :as str])
(import '(java.io File))

;; Load ops.clj as a library; package.json sets OPS_NO_AUTO_RUN in the
;; test environment so the script dispatcher does not auto-run.
(spit "/dev/null" "") ;; touch fs to ensure cwd is sane
(load-file (str (System/getProperty "user.dir") "/scripts/ops.clj"))

;; Safety net for unmocked `ops/die`. die normally calls
;; `(emit ...)` then `(System/exit 1)` — fine in production, fatal
;; in a test runner. Any test that exercises a code path which
;; eventually calls die without redefining it would silently kill
;; the JVM mid-suite: the failing test produces no assertion
;; output beyond `Testing user`, every subsequent test never runs,
;; and CI shows only `##[error]Process completed with exit code 1`.
;; CI was broken for six commits before commit 687dfc8 diagnosed
;; this. This fixture replaces ops/die at every test boundary so
;; any un-redef'd call throws cleanly — clojure.test reports it as
;; an :error against the offending test, the suite keeps running,
;; and the message names the fix.
;;
;; Tests that legitimately exercise die (e.g.
;; discover-emits-unsupported-build-tool-when-shadow-absent) keep
;; redefining it with their own `with-redefs` — that's dynamically
;; scoped and wins inside the test body.
(use-fixtures :each
  (fn [test-fn]
    (with-redefs [ops/die (fn [reason & {:as extra}]
                            (throw (ex-info
                                     (str "ops/die was called from a test without a "
                                          "with-redefs guard. die calls (System/exit 1) "
                                          "in production; this would have killed the "
                                          "JVM mid-suite. Add `ops/die` to your "
                                          "with-redefs (see "
                                          "discover-emits-unsupported-build-tool-when-shadow-absent "
                                          "for a pattern).")
                                     (assoc extra :reason reason))))
                  ;; Suppress live HTTP + cache I/O from the version-check
                  ;; (rfp-isvq) in every test. discover invokes
                  ;; version-check at the top, so without these mocks every
                  ;; discover-touching test would hit api.github.com (slow,
                  ;; network-flaky, rate-limited) and read/write a real
                  ;; cache file. Mocking the I/O primitives — not the
                  ;; orchestrator — leaves version-check itself testable:
                  ;; the dedicated `version-check-*` tests below override
                  ;; these with controlled values.
                  ops/fetch-latest-release (fn [] nil)
                  ops/read-version-cache   (fn [] nil)
                  ops/write-version-cache  (fn [_] nil)]
      (test-fn))))

;; ---------------------------------------------------------------------------
;; Meta — assert the safety fixture above actually catches an unmocked die.
;; If this ever fails, the fixture has drifted and the next regression like
;; the one diagnosed in 687dfc8 will silently kill the JVM in CI again.
;; ---------------------------------------------------------------------------

(deftest die-safety-fixture-catches-unmocked-call
  (testing "an unmocked ops/die throws ex-info (does not call System/exit)"
    (let [thrown (try (#'ops/die :sentinel :hint "test")
                      (catch clojure.lang.ExceptionInfo e
                        {:msg (.getMessage e) :data (ex-data e)}))]
      (is (= :sentinel (-> thrown :data :reason))
          ":reason from the call carried through the throw")
      (is (str/includes? (-> thrown :msg) "with-redefs")
          ":message names the fix (add ops/die to with-redefs)"))))

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
                    ops/inject-runtime!        (fn [_build-id opts]
                                                 (reset! seen opts)
                                                 {:ok? true
                                                  :ten-x-loaded? true
                                                  :trace-enabled? true
                                                  :re-com-debug? true
                                                  :versions {:by-dep {}}})
                    ops/startup-context        (fn [_build-id]
                                                 {:app-db {} :recent-events []})
                    ops/read-port              (fn [] 8777)
                    ;; See discover-emits-ambiguous-build-when-default-not-active
                    ;; for why this mock is required (#14 probe).
                    ops/shadow-cljs-available? (fn [_] true)
                    ops/list-builds-on-port    (fn [_] [:app])
                    ops/emit                   (fn [m] (reset! emitted m))]
        (#'ops/discover ["--no-capture"])
        (is (= {:capture? false} @seen))
        (is (true? (:capture-skipped? @emitted)))))))

(deftest discover-emits-startup-context
  (testing "successful discover includes current app-db + recent event orientation"
    (let [emitted (atom nil)]
      (with-redefs [ops/ensure-port!           (fn [] true)
                    ops/inject-runtime!        (fn [_build-id _opts]
                                                 {:ok? true
                                                  :ten-x-loaded? true
                                                  :trace-enabled? true
                                                  :re-com-debug? true
                                                  :versions {:by-dep {}}})
                    ops/startup-context        (fn [build-id]
                                                 {:app-db {:screen :checkout}
                                                  :recent-events [{:id 1 :event [:initialize]}]
                                                  :build-id build-id})
                    ops/read-port              (fn [] 8777)
                    ;; See discover-emits-ambiguous-build-when-default-not-active
                    ;; for why this mock is required (#14 probe).
                    ops/shadow-cljs-available? (fn [_] true)
                    ops/list-builds-on-port    (fn [_] [:app])
                    ops/emit                   (fn [m] (reset! emitted m))]
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
    (let [eval-calls   (atom [])
          probe-calls  (atom 0)]
      (with-redefs [;; First probe (fast-path skip): false → ship.
                    ;; Second probe (post-inject verify, added in issue #7 fix):
                    ;; true → confirm the runtime ns landed, no die.
                    ops/runtime-already-injected? (fn [_build-id]
                                                    (swap! probe-calls inc)
                                                    (>= @probe-calls 2))
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

;; ---------------------------------------------------------------------------
;; Wire-safe eval wrapping (issue #4 / bead rfp-zw3w).
;;
;; ops.clj wraps every user form in `re-frame-pair.runtime.wire/return!`
;; so the response is bounded by the wire-safe summary + cursor protocol.
;; Inject-path forms (source-ship, sentinel probe) must skip the wrap —
;; they run before the runtime is loaded, so referencing wire/return!
;; would itself fail with an undefined-ns error.
;; ---------------------------------------------------------------------------

(deftest wire-wrap-injects-return-call-by-default
  (testing "wire-wrap on a normal form prepends re-frame-pair.runtime.wire/return!"
    (let [wrapped (#'ops/wire-wrap "(re-frame.core/dispatch [:foo])")]
      (is (str/starts-with? wrapped "(re-frame-pair.runtime.wire/return!"))
      (is (str/includes? wrapped "(do (re-frame.core/dispatch [:foo]))"))
      (is (str/includes? wrapped ":budget-bytes")))))

(deftest wire-wrap-skips-when-form-is-already-a-return-or-raw
  (testing "operator-explicit return! / raw forms aren't double-wrapped"
    (let [already "(re-frame-pair.runtime.wire/return! v {})"]
      (is (= already (#'ops/wire-wrap already))))
    (let [raw "(re-frame-pair.runtime.wire/raw v)"]
      (is (= raw (#'ops/wire-wrap raw))))))

(deftest cljs-eval-form-default-wraps
  (testing "cljs-eval-form passes form-str through wire-wrap by default"
    (let [code (#'ops/cljs-eval-form :app "(some-form)")]
      (is (str/includes? code "re-frame-pair.runtime.wire/return!")))))

(deftest cljs-eval-form-wrap-false-skips-wire-wrap
  (testing ":wrap? false bypasses wire-wrap (inject path / sentinel probe)"
    (let [code (#'ops/cljs-eval-form :app "(some-form)" {:wrap? false})]
      (is (not (str/includes? code "re-frame-pair.runtime.wire/return!")))
      (is (str/includes? code "(some-form)")))))

(deftest cljs-eval-wire-unwraps-wire-shape
  (testing "wire-shape responses are unwrapped to {:value ... :rfp.wire/cursor ...}"
    (let [fake-resp {:value (pr-str
                             {:results
                              [(pr-str
                                {:rfp.wire/cursor   "abc/1"
                                 :rfp.wire/value    {:abridged true}
                                 :rfp.wire/elisions [{:path [:big]
                                                      :reason :branch-too-big}]})]})}]
      (with-redefs [ops/cljs-eval (fn [& _] fake-resp)]
        (let [result (#'ops/cljs-eval-wire nil :app "(any-form)" {})]
          (is (= {:abridged true} (:value result))
              "inner :rfp.wire/value surfaces as :value")
          (is (= "abc/1" (:rfp.wire/cursor result))
              ":rfp.wire/cursor preserved on the structured response")
          (is (= 1 (count (:rfp.wire/elisions result)))
              ":rfp.wire/elisions preserved when present")
          (is (false? (:rfp.wire/value-fits? result))
              ":rfp.wire/value-fits? false when elisions present"))))))

(deftest cljs-eval-wire-trivial-response-bare
  (testing "trivial responses pass through bare with :value-fits? true"
    (let [fake-resp {:value (pr-str {:results [(pr-str {:counter 0})]})}]
      (with-redefs [ops/cljs-eval (fn [& _] fake-resp)]
        (let [result (#'ops/cljs-eval-wire nil :app "(any-form)" {})]
          (is (= {:counter 0} (:value result)))
          (is (true? (:rfp.wire/value-fits? result)))
          (is (nil? (:rfp.wire/cursor result)))
          (is (nil? (:rfp.wire/elisions result))))))))

(deftest cljs-eval-value-strips-wire-shape-for-back-compat
  (testing "cljs-eval-value (legacy accessor) returns just the inner value"
    (let [fake-resp {:value (pr-str
                             {:results
                              [(pr-str
                                {:rfp.wire/cursor "x/1"
                                 :rfp.wire/value  {:counter 42}})]})}]
      (with-redefs [ops/cljs-eval (fn [& _] fake-resp)]
        (is (= {:counter 42} (#'ops/cljs-eval-value :app "(any-form)"))
            "wire shape unwrapped; existing callers see the inner value")))))

;; ---------------------------------------------------------------------------
;; Tests for rfp-isvq: discover surfaces :version + :version-check.
;; ---------------------------------------------------------------------------

(deftest current-version-reads-package-json
  (testing "current-version returns the version from the project's package.json"
    (let [v (#'ops/current-version)]
      (is (string? v))
      (is (re-matches #"\d+\.\d+\.\d+(-[a-z]+\.\d+)?" v)
          "version is a recognizable semver string"))))

(deftest version-check-nil-when-no-current-version
  (testing "no package.json (or unreadable) → version-check returns nil entirely"
    (with-redefs [ops/current-version      (fn [] nil)
                  ;; If current-version is nil, fetch should NEVER fire —
                  ;; we have nothing to compare against, no point in the
                  ;; round-trip.
                  ops/fetch-latest-release (fn [] (throw (ex-info "should-not-fetch" {})))]
      (is (nil? (#'ops/version-check))))))

(deftest version-check-status-current-when-versions-match
  (testing "local version matches latest GitHub release → :status :current"
    (with-redefs [ops/current-version      (fn [] "0.1.0-beta.6")
                  ops/read-version-cache   (fn [] nil)
                  ops/fetch-latest-release (fn []
                                             {:latest "0.1.0-beta.6"
                                              :released "2026-05-04T11:19:47Z"
                                              :url "https://example/v0.1.0-beta.6"})
                  ops/write-version-cache  (fn [_] nil)]
      (let [vc (#'ops/version-check)]
        (is (= :current (:status vc)))
        (is (= "0.1.0-beta.6" (:current vc)))
        (is (= "0.1.0-beta.6" (:latest vc)))))))

(deftest version-check-status-stale-when-versions-differ
  (testing "local version older than latest GitHub release → :status :stale"
    (with-redefs [ops/current-version      (fn [] "0.1.0-beta.6")
                  ops/read-version-cache   (fn [] nil)
                  ops/fetch-latest-release (fn []
                                             {:latest "0.1.0-beta.7"
                                              :released "2026-06-01T00:00:00Z"
                                              :url "https://example/v0.1.0-beta.7"})
                  ops/write-version-cache  (fn [_] nil)]
      (let [vc (#'ops/version-check)]
        (is (= :stale (:status vc)))
        (is (= "0.1.0-beta.6" (:current vc)))
        (is (= "0.1.0-beta.7" (:latest vc)))
        (is (str/includes? (:changelog vc) "v0.1.0-beta.7"))))))

(deftest version-check-status-unknown-when-fetch-fails
  (testing "fetch-latest-release returns nil (network down, rate-limited, etc.) → :status :unknown"
    (with-redefs [ops/current-version      (fn [] "0.1.0-beta.6")
                  ops/read-version-cache   (fn [] nil)
                  ops/fetch-latest-release (fn [] nil)]
      (let [vc (#'ops/version-check)]
        (is (= :unknown (:status vc)))
        (is (= "0.1.0-beta.6" (:current vc)))
        (is (nil? (:latest vc)))
        (is (nil? (:changelog vc)))))))

(deftest version-check-uses-cache-when-fresh
  (testing "fresh cache hit skips the network fetch"
    (let [fetch-called? (atom false)]
      (with-redefs [ops/current-version      (fn [] "0.1.0-beta.6")
                    ops/read-version-cache   (fn []
                                               {:latest "0.1.0-beta.6"
                                                :released "2026-05-04T11:19:47Z"
                                                :url "https://example/v0.1.0-beta.6"
                                                :checked-at (System/currentTimeMillis)})
                    ops/fetch-latest-release (fn [] (reset! fetch-called? true) nil)]
        (let [vc (#'ops/version-check)]
          (is (= :current (:status vc)))
          (is (false? @fetch-called?)
              "must NOT hit network when cache is fresh"))))))

(deftest discover-emit-includes-version-and-check
  (testing "discover's success-path emit carries :version and :version-check"
    (let [emitted (atom nil)]
      (with-redefs [ops/ensure-port!           (fn [] true)
                    ops/read-port              (fn [] 8777)
                    ops/shadow-cljs-available? (fn [_] true)
                    ops/list-builds-on-port    (fn [_] [:app])
                    ops/inject-runtime!        (fn [_build-id _opts]
                                                 {:ok? true
                                                  :ten-x-loaded? true
                                                  :ten-x-mounted? true
                                                  :trace-enabled? true
                                                  :re-com-debug? true
                                                  :versions {:by-dep {}}})
                    ops/startup-context        (fn [_build-id]
                                                 {:app-db {} :recent-events []})
                    ops/version-check          (fn []
                                                 {:current  "0.1.0-beta.6"
                                                  :status   :stale
                                                  :latest   "0.1.0-beta.7"
                                                  :released "2026-06-01T00:00:00Z"
                                                  :changelog "https://example/v0.1.0-beta.7"})
                    ops/emit                   (fn [m] (reset! emitted m))]
        (#'ops/discover [])
        (is (= "0.1.0-beta.6" (:version @emitted))
            ":version is the local version, lifted out of :current")
        (is (= :stale (-> @emitted :version-check :status)))
        (is (= "0.1.0-beta.7" (-> @emitted :version-check :latest)))
        (is (nil? (-> @emitted :version-check :current))
            ":current is removed from :version-check (it's already :version)")))))

(deftest discover-emit-includes-version-on-failure-paths-too
  (testing "version surface is also present on a failed discover (e.g. :ns-not-loaded)"
    (let [emitted (atom nil)]
      (with-redefs [ops/ensure-port!           (fn [] true)
                    ops/read-port              (fn [] 8777)
                    ops/shadow-cljs-available? (fn [_] true)
                    ops/list-builds-on-port    (fn [_] [:app])
                    ops/inject-runtime!        (fn [_build-id _opts]
                                                 {:ok? true
                                                  :ten-x-loaded? false
                                                  ;; #15 — also no native cb,
                                                  ;; so :ns-not-loaded fires.
                                                  :native-epoch-cb? false
                                                  :trace-enabled? true})
                    ops/startup-context        (fn [_build-id] {})
                    ops/version-check          (fn [] {:current "0.1.0-beta.6"
                                                       :status :current})
                    ops/emit                   (fn [m] (reset! emitted m))]
        (#'ops/discover [])
        (is (= :ns-not-loaded (:reason @emitted)) "still emits the failure reason")
        (is (= "0.1.0-beta.6" (:version @emitted))
            "operator reporting a bug always sees their version")))))

(deftest discover-proceeds-without-ten-x-when-native-epoch-cb-present
  (testing "#15 — discover does NOT block on missing 10x when native epoch cb is available"
    (let [emitted (atom nil)]
      (with-redefs [ops/ensure-port!           (fn [] true)
                    ops/read-port              (fn [] 8777)
                    ops/shadow-cljs-available? (fn [_] true)
                    ops/list-builds-on-port    (fn [_] [:app])
                    ops/inject-runtime!        (fn [_build-id _opts]
                                                 {:ok? true
                                                  :ten-x-loaded? false
                                                  :native-epoch-cb? true
                                                  :trace-enabled? true
                                                  :re-com-debug? true
                                                  :versions {:by-dep {}}})
                    ops/startup-context        (fn [_build-id] {})
                    ops/version-check          (fn [] nil)
                    ops/emit                   (fn [m] (reset! emitted m))]
        (#'ops/discover [])
        (is (true? (:ok? @emitted))
            "discover succeeds with no 10x because native epoch cb is the alternate epoch source")
        (is (not= :ns-not-loaded (:reason @emitted))
            "no :ns-not-loaded refusal when native cb is present")))))

(deftest discover-blocks-when-both-ten-x-and-native-cb-missing
  (testing "#15 — discover still blocks when neither 10x nor the native epoch cb is available"
    (let [emitted (atom nil)]
      (with-redefs [ops/ensure-port!           (fn [] true)
                    ops/read-port              (fn [] 8777)
                    ops/shadow-cljs-available? (fn [_] true)
                    ops/list-builds-on-port    (fn [_] [:app])
                    ops/inject-runtime!        (fn [_build-id _opts]
                                                 {:ok? true
                                                  :ten-x-loaded? false
                                                  :native-epoch-cb? false
                                                  :trace-enabled? true})
                    ops/startup-context        (fn [_build-id] {})
                    ops/version-check          (fn [] nil)
                    ops/emit                   (fn [m] (reset! emitted m))]
        (#'ops/discover [])
        (is (= :ns-not-loaded (:reason @emitted)) "still refuses when no epoch source at all")
        (is (str/includes? (:hint @emitted) "register-epoch-cb")
            ":hint mentions the native cb as an alternative to 10x")))))

;; ---------------------------------------------------------------------------
;; Tests for issue #18: tail-build's --probe must detect every
;; error↔value transition as a flip, not just value→value.
;;
;; The flipped? predicate is defined inline in tail-build-op so we
;; can't test it in isolation by var. Instead we exercise the same
;; logic by mocking cljs-eval-value: feed a sequence of return values
;; (or a sequence of throws), and assert tail-build-op emits the
;; right shape.
;; ---------------------------------------------------------------------------

(defn- run-tail-build-with-probe-sequence
  "Helper: drive tail-build-op with a controlled sequence of probe
   results. Each entry is either a value (returned) or
   {:throw <msg>} (cljs-eval-value throws). Returns the emitted map."
  [seq]
  (let [emitted (atom nil)
        idx     (atom -1)
        next!   (fn []
                  (let [step (nth seq (min (swap! idx inc) (dec (count seq))))]
                    (if (and (map? step) (:throw step))
                      (throw (Exception. ^String (:throw step)))
                      step)))]
    (with-redefs [ops/ensure-port!                  (fn [] true)
                  ops/ensure-injected!              (fn [_] false)
                  ops/with-current-nrepl-connection (fn [f] (f :fake-conn))
                  ops/cljs-eval-value               (fn [& _] (next!))
                  ops/emit                          (fn [m] (reset! emitted m))]
      ;; Use a tiny --wait-ms so timeout cases finish fast in the test.
      (#'ops/tail-build-op ["--probe" "(count foo/bar)" "--wait-ms" "300"])
      @emitted)))

(deftest tail-build-error-to-value-detected-as-flip
  (testing "#18 — probe error → value transition emits :ok? true (the after-edit add-new-form case)"
    (let [result (run-tail-build-with-probe-sequence
                   [{:throw "Use of undeclared Var foo/bar"}    ; before — undeclared
                    2])]                                         ; after reload — defined
      (is (true? (:ok? result))
          "error → value MUST register as a flip (the canonical add-new-form pattern)"))))

(deftest tail-build-value-to-error-detected-as-flip
  (testing "#18 — probe value → error transition emits :ok? true (symmetric removal case)"
    (let [result (run-tail-build-with-probe-sequence
                   [42                                           ; before — works
                    {:throw "Use of undeclared Var foo/bar"}])] ; after — removed
      (is (true? (:ok? result))
          "value → error is symmetric to the add case; treat as flip"))))

(deftest tail-build-value-to-value-flip-still-works
  (testing "#18 — value → value with inequality still flips (no regression)"
    (let [result (run-tail-build-with-probe-sequence
                   [1 1 1 2])]   ; baseline + two no-change polls + flip
      (is (true? (:ok? result))))))

(deftest tail-build-repl-exception-sentinel-treated-as-error
  (testing "#18 — :repl/exception! (shadow's printer-failure sentinel) is treated as error, so :repl/exception! → value flips"
    (let [result (run-tail-build-with-probe-sequence
                   [:repl/exception!                             ; before — sentinel
                    2])]                                         ; after — works
      (is (true? (:ok? result))
          ":repl/exception! is one of the error sentinels; transition to a real value is a flip"))))

(deftest tail-build-still-erroring-at-timeout-uses-compile-error-hint
  (testing "#18 — when probe is STILL erroring at --wait-ms, hint blames build / probe form"
    (let [result (run-tail-build-with-probe-sequence
                   (repeat 50 {:throw "Use of undeclared Var foo/bar"}))]
      (is (= :timed-out (:reason result)))
      (is (str/includes? (:note result) "Probe still errors")
          "hint specifically names the still-erroring case")
      (is (str/includes? (:note result) "compile error")
          "the original 'compile error / broken probe' guidance survives for this branch")
      (is (some? (:probe-error result))
          ":probe-error is the captured exception text"))))

(deftest tail-build-stable-value-at-timeout-uses-probe-choice-hint
  (testing "#18 — when probe returns the SAME value the whole window, hint blames probe-form choice (NOT compile error)"
    (let [result (run-tail-build-with-probe-sequence
                   (repeat 50 42))]   ; same value forever
      (is (= :timed-out (:reason result)))
      (is (not (str/includes? (:note result) "compile error"))
          "we don't blame the build when the probe is healthy")
      (is (str/includes? (:note result) "never differed from the baseline")
          "hint points at probe-form choice"))))

;; ---------------------------------------------------------------------------
;; Tests for issue #16: trace-recent.sh must surface parse failures as
;; structured EDN, not leak Java stack traces.
;; ---------------------------------------------------------------------------

(deftest trace-recent-non-integer-window-surfaces-bad-arg
  (testing "trace-recent with a non-integer window emits :reason :bad-arg via die, not a NumberFormatException"
    (let [die-args (atom nil)]
      (with-redefs [ops/ensure-port! (fn [] true)
                    ;; die is auto-throw-mocked by the :each fixture for safety;
                    ;; this test additionally captures the args.
                    ops/die          (fn [reason & {:as extra}]
                                       (reset! die-args (assoc extra :reason reason))
                                       (throw (ex-info "die" {:reason reason})))]
        (try (#'ops/trace-recent-op ["--limit"])
             (catch clojure.lang.ExceptionInfo _))
        (is (= :bad-arg (:reason @die-args))
            "structured :reason instead of bare NumberFormatException")
        (is (= "--limit" (:got @die-args))
            ":got carries the offending input verbatim")
        (is (str/includes? (:hint @die-args) "milliseconds")
            ":hint clarifies that the window is in ms, not a count (the natural mistake)")))))

(deftest trace-recent-valid-integer-window-passes-through
  (testing "trace-recent with a valid integer window proceeds past parse without die-ing"
    (let [die-called? (atom false)]
      (with-redefs [ops/ensure-port!     (fn [] true)
                    ops/die              (fn [& _]
                                           (reset! die-called? true)
                                           (throw (ex-info "should-not-die" {})))
                    ops/ensure-injected! (fn [_] false)
                    ops/cljs-eval-value  (fn [& _] [])
                    ops/emit             (fn [_] nil)]
        (#'ops/trace-recent-op ["3000"])
        (is (false? @die-called?) "valid integer arg does not trigger any die")))))

;; ---------------------------------------------------------------------------
;; Tests for issue #14: refuse non-shadow-cljs nREPLs early with a structured
;; :unsupported-build-tool, instead of crashing on the first call to
;; (shadow.cljs.devtools.api/...).
;; ---------------------------------------------------------------------------

(deftest shadow-cljs-available-detects-shadow
  (testing "probe returns true when nrepl-eval-raw resolves to ':ok'"
    (with-redefs [ops/nrepl-eval-raw     (fn [_ _] :unused)
                  ops/combine-responses  (fn [_] {:value ":ok"})]
      (is (true? (#'ops/shadow-cljs-available? 8777))))))

(deftest shadow-cljs-available-detects-non-shadow
  (testing "probe returns false when require throws (e.g. figwheel nREPL with no shadow ns)"
    (with-redefs [ops/nrepl-eval-raw (fn [_ _]
                                       (throw (ex-info "FileNotFoundException for shadow.cljs.devtools.api" {})))]
      (is (false? (#'ops/shadow-cljs-available? 8777))))))

(deftest shadow-cljs-available-treats-non-ok-value-as-false
  (testing "probe returns false when value isn't ':ok' (defensive against odd shadow shapes)"
    (with-redefs [ops/nrepl-eval-raw    (fn [_ _] :unused)
                  ops/combine-responses (fn [_] {:value "nil"})]
      (is (false? (#'ops/shadow-cljs-available? 8777))))))

(deftest discover-emits-unsupported-build-tool-when-shadow-absent
  (testing "discover refuses early on non-shadow nREPL with structured :unsupported-build-tool"
    (let [die-args (atom nil)]
      (with-redefs [ops/ensure-port!           (fn [] true)
                    ops/read-port              (fn [] 8777)
                    ops/shadow-cljs-available? (fn [_] false)
                    ;; die normally calls (emit) (System/exit 1). The
                    ;; redef captures args + throws so subsequent code
                    ;; in discover doesn't run against a real nREPL.
                    ops/die                    (fn [reason & {:as extra}]
                                                 (reset! die-args (assoc extra :reason reason))
                                                 (throw (ex-info "die" {:reason reason})))]
        (try (#'ops/discover [])
             (catch clojure.lang.ExceptionInfo _))
        (is (= :unsupported-build-tool (:reason @die-args)))
        (is (str/includes? (:hint @die-args) "shadow-cljs"))
        (is (str/includes? (:hint @die-args) "issues/14"))))))

;; ---------------------------------------------------------------------------
;; Tests for issue #6 (deeper fix): parse-cljs-eval-response must surface
;; errors buried inside shadow's :value map (`{:results [] :err "..."}`)
;; instead of returning nil. Most common buried error is
;; "No available JS runtime." when the browser tab isn't registered.
;; ---------------------------------------------------------------------------

(deftest parse-surfaces-no-available-js-runtime-as-browser-runtime-not-attached
  (testing "buried 'No available JS runtime.' surfaces as :reason :browser-runtime-not-attached"
    (let [res {:value (pr-str
                        {:results []
                         :out ""
                         :err "No available JS runtime.\nSee https://shadow-cljs.github.io/docs/UsersGuide.html#repl-troubleshooting"
                         :ns 'cljs.user})}
          thrown (try (#'ops/parse-cljs-eval-response res)
                      (catch clojure.lang.ExceptionInfo e
                        {:msg (.getMessage e) :data (ex-data e)}))]
      (is (= :browser-runtime-not-attached (-> thrown :data :reason)))
      (is (str/includes? (-> thrown :data :err) "No available JS runtime"))
      (is (str/includes? (-> thrown :data :hint) "browser tab")
          ":hint guides operator to open / hard-refresh the tab"))))

(deftest parse-surfaces-buried-undeclared-var-as-cljs-eval-error
  (testing "buried :undeclared-var warning surfaces as :reason :cljs-eval-error"
    (let [res {:value (pr-str
                        {:results []
                         :out ""
                         :err "------ WARNING - :undeclared-var -----------------------------------------------\n Resource: <eval>:1:1\n Use of undeclared Var foo/bar"
                         :ns 'cljs.user})}
          thrown (try (#'ops/parse-cljs-eval-response res)
                      (catch clojure.lang.ExceptionInfo e
                        (ex-data e)))]
      (is (= :cljs-eval-error (:reason thrown)))
      (is (str/includes? (:err thrown) ":undeclared-var")))))

(deftest parse-still-returns-3-for-happy-path
  (testing "no regression: shadow's normal :results [\"3\"] shape parses to 3"
    (let [res {:value (pr-str
                        {:results ["3"]
                         :out ""
                         :err ""
                         :ns 'cljs.user})}]
      (is (= 3 (#'ops/parse-cljs-eval-response res))))))

(deftest parse-empty-results-no-error-still-returns-nil
  (testing "no regression: empty results AND blank err keeps the historical nil return"
    (let [res {:value (pr-str
                        {:results []
                         :out ""
                         :err ""
                         :ns 'cljs.user})}]
      (is (nil? (#'ops/parse-cljs-eval-response res))))))

;; ---------------------------------------------------------------------------
;; Tests for issue #6: cljs-eval-wire must distinguish "form returned nil"
;; from "shadow returned a blank response with no error" — the latter used
;; to surface as the misleading {:ok? true :value nil}.
;; ---------------------------------------------------------------------------

(deftest cljs-eval-wire-throws-on-blank-no-error-response
  (testing "blank :value with no :err and no :ex throws :cljs-eval-empty (eval-op surfaces as {:ok? false ...})"
    (with-redefs [ops/cljs-eval (fn [& _] {:value "" :err "" :ex nil :status #{:done}})]
      (let [thrown (try (#'ops/cljs-eval-wire nil :app "(+ 1 2)" {})
                        (catch clojure.lang.ExceptionInfo e
                          {:msg (.getMessage e) :data (ex-data e)}))]
        (is (= :cljs-eval-empty (-> thrown :data :reason))
            ":cljs-eval-empty distinguishes empty from legitimate-nil")
        (is (contains? (:data thrown) :raw-response)
            ":raw-response carried for debugging")
        (is (str/includes? (-> thrown :data :hint) "discover-app"))))))

(deftest cljs-eval-wire-passes-through-legitimate-nil-value
  (testing "shadow returning :value \"nil\" (the EDN string) is a real nil result, not an empty response"
    (with-redefs [ops/cljs-eval (fn [& _] {:value (pr-str {:results [(pr-str nil)]})
                                            :status #{:done}})]
      (let [result (#'ops/cljs-eval-wire nil :app "(prn :hi)" {})]
        (is (nil? (:value result)) "legitimate nil passes through as :value nil")
        (is (true? (:rfp.wire/value-fits? result)) "no error surfaced")))))

(deftest cljs-eval-wire-blank-with-err-keeps-existing-eval-error-path
  (testing "blank :value WITH :err set is an eval error, not :cljs-eval-empty (parse-cljs-eval-response throws first)"
    (with-redefs [ops/cljs-eval (fn [& _] {:value "" :err "" :ex "compile error somewhere"})]
      (let [thrown (try (#'ops/cljs-eval-wire nil :app "(broken)" {})
                        (catch clojure.lang.ExceptionInfo e
                          (ex-data e)))]
        (is (= :eval-error (:reason thrown)) "throws :eval-error from parse-cljs-eval-response, not :cljs-eval-empty")))))

(deftest cljs-eval-wire-blank-with-only-err-string-still-throws-cljs-eval-empty
  (testing "blank :value with empty :err string but no :ex falls into :cljs-eval-empty (the headline #6 shape)"
    (with-redefs [ops/cljs-eval (fn [& _] {:value "" :err nil :ex nil :status #{:done}})]
      (let [thrown (try (#'ops/cljs-eval-wire nil :app "(+ 1 2)" {})
                        (catch clojure.lang.ExceptionInfo e
                          (ex-data e)))]
        (is (= :cljs-eval-empty (:reason thrown)))))))

;; ---------------------------------------------------------------------------
;; Tests for issue #7: inject failures must surface as :inject-failed, not
;; pass through silently as nil from downstream ops.
;; ---------------------------------------------------------------------------

(deftest inject-failure-detects-shadow-build-resolve
  (testing "inject-failure recognizes :shadow.build.resolve/missing-ns as :inject-failed :compile"
    (let [resp {:err (str "ExceptionInfo The required namespace "
                          "\"re-frame-pair.runtime.ten-x-adapter\" is not available, "
                          "it was required by \"re_frame_pair/runtime/registrar.cljs\". "
                          "{:tag :shadow.build.resolve/missing-ns ...}")}
          fail (#'ops/inject-failure resp)]
      (is (= :inject-failed (:reason fail)))
      (is (= :compile (:stage fail)))
      (is (str/includes? (:hint fail) "load-order"))
      (is (str/includes? (:hint fail) "npm test:ops")))))

(deftest inject-failure-detects-build-resolve-without-exception-prefix
  (testing "matches the bare 'required namespace ... is not available' pattern even without ExceptionInfo prefix"
    (let [resp {:err "The required namespace \"foo.bar\" is not available."}
          fail (#'ops/inject-failure resp)]
      (is (= :inject-failed (:reason fail)))
      (is (= :compile (:stage fail))))))

(deftest inject-failure-still-returns-nil-on-clean-response
  (testing "no :ex and an :err that matches no known pattern → success (nil)"
    (is (nil? (#'ops/inject-failure {:value "OK"})))
    (is (nil? (#'ops/inject-failure {:value "OK" :err ""})))))

(deftest ensure-injected-dies-when-sentinel-missing-post-inject
  (testing "chunked ship reports clean but runtime ns isn't reachable → die :inject-failed :verify"
    (let [die-args (atom nil)]
      (with-redefs [ops/die                       (fn [reason & {:as extra}]
                                                    (reset! die-args (assoc extra :reason reason)))
                    ops/runtime-already-injected? (fn [_] false)
                    ops/chunked-inject!           (fn [_ _] [[0 {:value "OK"}]])
                    ops/cljs-eval-value           (fn [& _] nil)
                    ops/runtime-cljs-paths        (fn [] ["dummy.cljs"])]
        (#'ops/ensure-injected! :app)
        (is (= :inject-failed (:reason @die-args)))
        (is (= :verify (:stage @die-args)))
        (is (str/includes? (:hint @die-args) "shadow"))
        (is (str/includes? (:hint @die-args) "watch"))))))

(deftest ensure-injected-succeeds-when-sentinel-present-post-inject
  (testing "chunked ship clean AND sentinel present post-inject → returns true, no die"
    (let [die-called? (atom false)
          probe-calls (atom 0)]
      (with-redefs [ops/die                       (fn [& _] (reset! die-called? true))
                    ;; First probe (fast-path skip): false → proceed to ship.
                    ;; Second probe (verify): true → success.
                    ops/runtime-already-injected? (fn [_]
                                                    (swap! probe-calls inc)
                                                    (= 2 @probe-calls))
                    ops/chunked-inject!           (fn [_ _] [[0 {:value "OK"}]])
                    ops/cljs-eval-value           (fn [& _] nil)
                    ops/runtime-cljs-paths        (fn [] ["dummy.cljs"])]
        (let [result (#'ops/ensure-injected! :app)]
          (is (true? result) "returns true (re-shipped)")
          (is (false? @die-called?) "must NOT call die when sentinel verifies")
          (is (= 2 @probe-calls) "must call sentinel probe a second time post-inject"))))))

;; ---------------------------------------------------------------------------
;; Tests for issue #9: discover-app.sh should emit :ambiguous-build instead
;; of silently picking a default build that isn't active.
;; ---------------------------------------------------------------------------

(deftest ambiguous-build-pred-explicit-selection-never-ambiguous
  (testing "if the operator named the build, never ambiguous"
    (is (false? (#'ops/ambiguous-build? true :app [:karma-test :browser-test])))
    (is (false? (#'ops/ambiguous-build? true :app [])))
    (is (false? (#'ops/ambiguous-build? true :app nil)))))

(deftest ambiguous-build-pred-default-in-list-not-ambiguous
  (testing "if the default IS one of the active builds, not ambiguous"
    (is (false? (#'ops/ambiguous-build? false :app [:app])))
    (is (false? (#'ops/ambiguous-build? false :app [:app :browser-test])))))

(deftest ambiguous-build-pred-default-not-in-list-is-ambiguous
  (testing "default not in candidate list, no explicit selection — ambiguous"
    (is (true? (#'ops/ambiguous-build? false :app [:karma-test :browser-test :mws2prp])))
    (is (true? (#'ops/ambiguous-build? false :app [:storybook])))))

(deftest ambiguous-build-pred-empty-or-nil-builds-not-ambiguous
  (testing "probe failed (nil) or returned [] — fall through to existing inject path, not ambiguous"
    (is (false? (#'ops/ambiguous-build? false :app nil)))
    (is (false? (#'ops/ambiguous-build? false :app [])))))

(deftest discover-emits-ambiguous-build-when-default-not-active
  (testing "discover refuses to inject and emits structured :ambiguous-build response"
    (let [emitted (atom nil)
          inject-called? (atom false)]
      (with-redefs [ops/ensure-port!           (fn [] true)
                    ops/read-port              (fn [] 8777)
                    ;; #14 added a shadow-cljs-availability probe in
                    ;; discover before the ambiguous-build check. Mock
                    ;; it true here so the test exercises the
                    ;; ambiguous-build path, not the unsupported-build
                    ;; path. Without this mock the real probe runs
                    ;; against a non-existent nREPL and the unmocked
                    ;; die calls System/exit, killing the JVM mid-test.
                    ops/shadow-cljs-available? (fn [_] true)
                    ops/list-builds-on-port    (fn [_] [:karma-test :browser-test :mws2prp])
                    ops/inject-runtime!        (fn [& _] (reset! inject-called? true) {})
                    ops/emit                   (fn [m] (reset! emitted m))]
        (#'ops/discover [])
        (is (false? @inject-called?) "must NOT call inject-runtime! when default is not active")
        (is (= false (:ok? @emitted)))
        (is (= :ambiguous-build (:reason @emitted)))
        (is (= [:karma-test :browser-test :mws2prp] (:candidates @emitted)))
        (is (= :app (:picked-default @emitted)))
        (is (str/includes? (:hint @emitted) "--build="))))))

;; ---------------------------------------------------------------------------
;; Tests for rfp-gmkj: load-order topology check on runtime-submodule-files
;;
;; PR #5 fixed registrar.cljs being listed before ten-x-adapter.cljs in
;; ops/runtime-submodule-files despite registrar :requireing ten-x-adapter.
;; That ordering trips :shadow.build.resolve/missing-ns during the chunked
;; source-ship and silently aborts the inject. These tests pin the
;; invariant: every (:require re-frame-pair.runtime.X) in a submodule must
;; appear at an earlier slot in the load-order list, and every .cljs file
;; in scripts/re_frame_pair/runtime/ must be listed.
;; ---------------------------------------------------------------------------

(defn- runtime-file->ns
  "\"ten_x_adapter.cljs\" -> 're-frame-pair.runtime.ten-x-adapter"
  [filename]
  (-> filename
      (str/replace #"\.cljs$" "")
      (str/replace "_" "-")
      (->> (str "re-frame-pair.runtime.")
           symbol)))

(defn- read-ns-form
  "Read the first top-level form of a CLJS file. Expected to be (ns ...)."
  [path]
  (with-open [rdr (java.io.PushbackReader. (io/reader (io/file path)))]
    (read rdr)))

(defn- runtime-requires
  "Set of fully-qualified re-frame-pair.runtime.* namespaces required by
   the given (ns ...) form. Skips :require-macros and other clauses."
  [ns-form]
  (let [require-clause (->> ns-form
                            (filter #(and (seq? %) (= :require (first %))))
                            first)
        required       (->> (rest require-clause)
                            (map (fn [r] (if (sequential? r) (first r) r))))]
    (->> required
         (filter symbol?)
         (filter #(str/starts-with? (str %) "re-frame-pair.runtime."))
         set)))

(def ^:private runtime-submodule-dir
  (io/file (System/getProperty "user.dir")
           "scripts" "re_frame_pair" "runtime"))

(deftest runtime-submodule-files-respects-require-graph
  (testing "every (:require re-frame-pair.runtime.X) appears earlier in the load order"
    (let [files      @#'ops/runtime-submodule-files
          ns->slot   (into {} (map-indexed (fn [i f] [(runtime-file->ns f) i]) files))
          violations (for [[i filename] (map-indexed vector files)
                           :let [path    (io/file runtime-submodule-dir filename)
                                 ns-name (runtime-file->ns filename)
                                 deps    (runtime-requires (read-ns-form path))
                                 mis     (filter (fn [d]
                                                   (when-let [dep-slot (ns->slot d)]
                                                     (>= dep-slot i)))
                                                 deps)]
                           :when (seq mis)]
                       {:file                  filename
                        :slot                  i
                        :ns                    ns-name
                        :requires-loaded-later (vec mis)})]
      (is (empty? violations)
          (str "Mis-ordered runtime submodules in scripts/ops.clj/runtime-submodule-files. "
               "Move each :file to AFTER every ns in :requires-loaded-later:\n"
               (str/join "\n" (map pr-str violations)))))))

(deftest runtime-submodule-files-covers-every-cljs-in-runtime-dir
  (testing "every .cljs in scripts/re_frame_pair/runtime/ is listed in runtime-submodule-files"
    (let [on-disk (->> (.listFiles runtime-submodule-dir)
                       (map #(.getName %))
                       (filter #(str/ends-with? % ".cljs"))
                       set)
          listed  (set @#'ops/runtime-submodule-files)
          missing (clojure.set/difference on-disk listed)
          extra   (clojure.set/difference listed on-disk)]
      (is (empty? missing)
          (str "These submodule files exist on disk but are not listed in "
               "ops/runtime-submodule-files (they will not be shipped on inject): "
               (pr-str missing)))
      (is (empty? extra)
          (str "These names are in ops/runtime-submodule-files but the file does not exist: "
               (pr-str extra))))))

(let [{:keys [fail error]} (run-tests 'user)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
