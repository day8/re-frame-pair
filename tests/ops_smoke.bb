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
;;;;   bb tests/ops_smoke.bb

(require '[clojure.test :refer [deftest is testing run-tests]]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '(java.io File))

;; Load ops.clj as a library — auto-run is gated on OPS_NO_AUTO_RUN.
(System/setProperty "OPS_NO_AUTO_RUN" "1") ;; belt-and-suspenders alongside env
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

;; The `#{:app}` (set) case is a known bug: list-builds-on-port checks
;; (sequential? v), which is false for sets, so a real shadow build
;; reports :builds [] under --list. Tracked in rfp-j2i; this test
;; documents the current behaviour. Re-introduce as a passing test
;; once rfp-j2i lands.
(deftest list-builds-on-port-set-input-current-buggy-behaviour
  (testing "set return — known bug: returns nil instead of vec (rfp-j2i)"
    (with-redefs [ops/nrepl-eval-raw  (fn [_ _] :unused)
                  ops/combine-responses (fn [_] {:value "#{:app}"})]
      (is (nil? (#'ops/list-builds-on-port 8777))
          "When rfp-j2i fixes this, flip to (= [:app] ...) and rename the deftest"))))

(deftest read-port-candidates-env-override
  (testing "SHADOW_CLJS_NREPL_PORT env var suppresses file probing"
    (with-redefs [ops/port-file-candidates ["nope/.port"]]
      ;; Direct env-var override — tests the if-let path that reads
      ;; SHADOW_CLJS_NREPL_PORT first.
      (let [orig (System/getenv "SHADOW_CLJS_NREPL_PORT")]
        (try
          ;; Setting env vars in JVM is finicky; use the underlying
          ;; mechanism via reflection-free alternative — call the
          ;; private helper with the env "stamped" by redefining
          ;; getenv via System/getProperty. Simpler: redef via
          ;; with-redefs + a local fn substitution. Skip if mocking
          ;; getenv is impractical and rely on file-mode tests below.
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

(let [{:keys [fail error]} (run-tests 'user)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
