#!/usr/bin/env bb
;;;; tests/skill_recipe_smoke.bb — contract tests for SKILL.md recipes.
;;;;
;;;; Why this file exists
;;;; ---------------------
;;;; SKILL.md prescribes the wrap-handler! / unwrap-handler! procedure
;;;; (the runtime-API path of the "Trace a handler / sub / fx form-by-form"
;;;; recipe) but until now no automated test covered it. The recipe runs
;;;; against day8.re-frame.tracing.runtime in the live fixture, which
;;;; isn't exercised by `npm test` (no shadow-cljs+browser harness in CI).
;;;;
;;;; This runner closes the contract gap textually:
;;;;   1. Each `scripts/eval-cljs.sh '...'` form in the recipe parses
;;;;      cleanly via tools.reader (catches syntax / form-shape drift —
;;;;      a mis-edit of SKILL.md that drops a paren or breaks a quote).
;;;;   2. The recipe's calls reference the expected qualified symbols
;;;;      (catches a rename in upstream debux's tracing.runtime ns;
;;;;      runtime-API path uses wrap-handler! / wrap-sub! / wrap-fx! /
;;;;      unwrap-handler!, manual fn-traced fallback uses fn-traced).
;;;;   3. tests/fixture/deps.edn still pins day8.re-frame/tracing as
;;;;      :local/root. Without this pin the recipe silently fails —
;;;;      the macros aren't on the classpath and the eval-cljs.sh form
;;;;      errors out at compile time.
;;;;   4. re-frame-pair.runtime/debux-runtime-api? still exists in
;;;;      runtime.cljs (the recipe's first call is the detection probe).
;;;;
;;;; What this DOES NOT cover: that the wrapped handler actually runs
;;;; under fn-traced and produces a :code trace at runtime. That requires
;;;; a live CLJS+browser harness; see tests/fixture/src/app/
;;;; wrap_handler_smoke.cljs for the operator-runnable smoke that does
;;;; verify the wrap → dispatch → unwrap cycle end-to-end.
;;;;
;;;; Run via `npm run test:skill-recipe` (see package.json) or directly:
;;;;   bb tests/skill_recipe_smoke.bb

(require '[clojure.test :refer [deftest is testing run-tests]]
         '[clojure.string :as str]
         '[clojure.tools.reader :as reader]
         '[clojure.edn :as edn])

(def ^:private project-root (System/getProperty "user.dir"))
(def ^:private skill-md     (slurp (str project-root "/SKILL.md")))
(def ^:private fixture-deps-path
  (str project-root "/tests/fixture/deps.edn"))
(def ^:private runtime-cljs-path
  (str project-root "/scripts/re_frame_pair/runtime.cljs"))

(defn- extract-eval-cljs-forms
  "Vec of CLJS source strings inside every `scripts/eval-cljs.sh '...'`
   invocation in `s`. Multi-line forms are returned as one string with
   embedded newlines. The `[^']*` body assumes recipe forms don't quote
   their inner forms — true for every recipe in the wrap-handler! /
   manual fn-traced regions; if a future recipe edit needs `'foo`-style
   quoting inside the eval payload, swap to `--cljs-form` markers
   instead of regex-extracting from prose."
  [s]
  (->> (re-seq #"(?s)scripts/eval-cljs\.sh\s+'([^']*)'" s)
       (mapv second)))

(defn- read-form
  "Parse `cljs-src` as a single CLJS form. Returns the form on success;
   throws on bad syntax. Reader-conditional :cljs feature is enabled so
   any `#?(:cljs ...)` branch resolves the way it would in the live
   fixture build."
  [cljs-src]
  (reader/read-string {:read-cond :allow :features #{:cljs}} cljs-src))

(defn- form-symbols
  "Set of fully-qualified symbols referenced inside `form`. Bare
   symbols (no namespace) are dropped — the contract is about the
   fully-qualified API surface the recipe documents."
  [form]
  (->> form
       (tree-seq coll? seq)
       (filter symbol?)
       (filter namespace)
       set))

(defn- region-between
  "Slice of `s` from `start-marker` (inclusive) to `end-marker`
   (exclusive). Asserts both markers are present so a future SKILL.md
   restructure that drops them fails loudly here."
  [s start-marker end-marker]
  (let [start (str/index-of s start-marker)
        end   (str/index-of s end-marker)]
    (assert start (str "marker not found: " start-marker))
    (assert end   (str "marker not found: " end-marker))
    (subs s start end)))

;; ---------------------------------------------------------------------------
;; Region slices — locked to specific markdown headers so a future
;; SKILL.md restructure either updates these or fails the test loudly.
;; ---------------------------------------------------------------------------

(def ^:private runtime-api-region
  (region-between skill-md
                  "**Procedure (runtime API"
                  "**Procedure (manual fn-traced"))

(def ^:private manual-fallback-region
  (region-between skill-md
                  "**Procedure (manual fn-traced"
                  "**Limits to call out to the user"))

;; ---------------------------------------------------------------------------
;; Tests — recipe forms parse cleanly via tools.reader.
;; ---------------------------------------------------------------------------

(deftest runtime-api-recipe-forms-parse
  (testing "every `scripts/eval-cljs.sh '...'` form in the runtime-API
            section of the wrap-handler!/unwrap-handler! recipe parses
            as a single CLJS form. An unbalanced paren or missing
            closing token introduced by a future SKILL.md edit would
            fail here before the recipe ships to agents."
    (let [forms (extract-eval-cljs-forms runtime-api-region)]
      (is (= 4 (count forms))
          (str "expected 4 eval-cljs.sh forms in runtime-API region "
               "(wrap-handler!, wrap-sub!, wrap-fx!, unwrap-handler!); "
               "got " (count forms)))
      (doseq [src forms]
        (is (some? (read-form src))
            (str "form failed to parse: " (pr-str src)))))))

(deftest manual-fallback-recipe-forms-parse
  (testing "every `scripts/eval-cljs.sh '...'` form in the manual
            fn-traced fallback section parses as a single CLJS form."
    (let [forms (extract-eval-cljs-forms manual-fallback-region)]
      (is (= 2 (count forms))
          (str "expected 2 eval-cljs.sh forms in manual-fallback region "
               "(get-handler, reg-event-db with fn-traced); "
               "got " (count forms)))
      (doseq [src forms]
        (is (some? (read-form src))
            (str "form failed to parse: " (pr-str src)))))))

;; ---------------------------------------------------------------------------
;; Tests — recipe forms reference the expected qualified symbols.
;; ---------------------------------------------------------------------------

(def ^:private runtime-api-expected-symbols
  "Qualified symbols the runtime-API path of the recipe must call. Drift
   here means SKILL.md references an API surface that's been renamed
   away — the eval-cljs.sh form would compile-fail at the user's REPL."
  '#{day8.re-frame.tracing.runtime/wrap-handler!
     day8.re-frame.tracing.runtime/wrap-sub!
     day8.re-frame.tracing.runtime/wrap-fx!
     day8.re-frame.tracing.runtime/unwrap-handler!})

(deftest runtime-api-recipe-references-expected-symbols
  (testing "the runtime-API recipe still calls wrap-handler!, wrap-sub!,
            wrap-fx!, and unwrap-handler! from
            day8.re-frame.tracing.runtime"
    (let [referenced (->> (extract-eval-cljs-forms runtime-api-region)
                          (mapcat (fn [src] (form-symbols (read-form src))))
                          set)]
      (doseq [sym runtime-api-expected-symbols]
        (is (contains? referenced sym)
            (str "recipe no longer references " sym))))))

(def ^:private manual-fallback-expected-symbols
  "Qualified symbols the manual fn-traced fallback path of the recipe
   must call. The fallback exists for debux releases that predate the
   runtime-API ns (rfd-8g9 / 4ed07c9) — losing the symbol references
   here means the fallback no longer documents a reproducible procedure."
  '#{re-frame.core/reg-event-db
     re-frame.registrar/get-handler
     day8.re-frame.tracing/fn-traced})

(deftest manual-fallback-recipe-references-expected-symbols
  (testing "the manual fn-traced fallback still calls reg-event-db,
            registrar/get-handler, and the day8.re-frame.tracing/fn-traced
            macro by name"
    (let [referenced (->> (extract-eval-cljs-forms manual-fallback-region)
                          (mapcat (fn [src] (form-symbols (read-form src))))
                          set)]
      (doseq [sym manual-fallback-expected-symbols]
        (is (contains? referenced sym)
            (str "fallback no longer references " sym))))))

;; ---------------------------------------------------------------------------
;; Tests — fixture deps.edn still pins day8.re-frame/tracing.
;; ---------------------------------------------------------------------------

(deftest fixture-deps-pins-day8-tracing-local-root
  (testing "tests/fixture/deps.edn keeps day8.re-frame/tracing wired as
            :local/root. Without this pin the wrap-handler! recipe
            silently fails — the macros aren't on the classpath and
            the eval-cljs.sh form errors out at compile time."
    (let [deps-form (edn/read-string (slurp fixture-deps-path))
          tracing   (get-in deps-form [:deps 'day8.re-frame/tracing])]
      (is (some? tracing)
          "day8.re-frame/tracing dep is missing from tests/fixture/deps.edn")
      (is (contains? tracing :local/root)
          (str "day8.re-frame/tracing must be :local/root-pinned for the "
               "fixture (sibling :local/root re-frame-debux); got "
               (pr-str tracing))))))

;; ---------------------------------------------------------------------------
;; Tests — runtime.cljs still defines debux-runtime-api?.
;; ---------------------------------------------------------------------------

(deftest debux-runtime-api-probe-still-defined
  (testing "the recipe's first call — (re-frame-pair.runtime/debux-runtime-api?) —
            still resolves to a defn in runtime.cljs. A rename of the
            detection probe would silently strand the recipe in
            'unknown availability' mode (the eval would error and the
            agent would fall through to the manual fallback for no
            reason)."
    (let [src (slurp runtime-cljs-path)]
      (is (str/includes? src "(defn debux-runtime-api?")
          "debux-runtime-api? defn missing from runtime.cljs"))))

(let [{:keys [fail error]} (run-tests 'user)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
