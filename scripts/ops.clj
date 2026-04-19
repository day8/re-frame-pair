#!/usr/bin/env bb
;;;; scripts/ops.clj — babashka entry point for all re-frame-pair ops.
;;;;
;;;; Each `scripts/*.sh` is a thin wrapper that exec's:
;;;;     bb ops.clj <subcommand> [args...]
;;;;
;;;; Subcommands:
;;;;   discover   — locate shadow-cljs nREPL, verify prerequisites,
;;;;                inject runtime, report {:ok? ...}
;;;;   eval       — cljs-eval a form, return edn result
;;;;   inject     — (re-)inject re-frame-pair.runtime
;;;;   dispatch   — fire an event; --sync / --trace variants
;;;;   trace-recent — epochs added in the last N ms
;;;;   watch      — pull-mode live streaming of matching epochs
;;;;   tail-build — wait for hot-reload to land; probe-form gated
;;;;
;;;; All ops return edn on stdout. Shells capture and forward.
;;;;
;;;; NOTE: this is an initial implementation. Several ops depend on
;;;; re-frame-10x internals that are not confirmed against a live
;;;; fixture yet (see docs/initial-spec.md §8a). Where uncertain, the
;;;; code stubs out with a :reason :pending-spike result rather than
;;;; guessing.

(ns ops
  (:require [babashka.nrepl-client :as nrepl]
            [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Config / env
;; ---------------------------------------------------------------------------

(def default-build-id
  (keyword (or (System/getenv "SHADOW_CLJS_BUILD_ID") "app")))

(def port-file-candidates
  ["target/shadow-cljs/nrepl.port"
   ".shadow-cljs/nrepl.port"
   ".nrepl-port"])

(defn- read-port
  "Locate the shadow-cljs nREPL port."
  []
  (or (when-let [p (System/getenv "SHADOW_CLJS_NREPL_PORT")]
        (Integer/parseInt p))
      (some (fn [path]
              (let [f (io/file path)]
                (when (.exists f)
                  (Integer/parseInt (str/trim (slurp f))))))
            port-file-candidates)))

(defn- runtime-cljs-path []
  (.getPath (io/file (.getParent (io/file *file*)) "runtime.cljs")))

;; ---------------------------------------------------------------------------
;; Output helpers
;; ---------------------------------------------------------------------------

(defn- emit
  "Print a structured result as edn on stdout."
  [m]
  (binding [*out* *out*]
    (pr m)
    (println))
  (flush))

(defn- die
  "Emit error result and exit 1."
  [reason & {:as extra}]
  (emit (merge {:ok? false :reason reason} extra))
  (System/exit 1))

;; ---------------------------------------------------------------------------
;; nREPL conveniences
;; ---------------------------------------------------------------------------

(defn- jvm-eval
  "Evaluate a Clojure (JVM-side) form over nREPL and return the parsed
   result, or a structured error."
  [form-str]
  (let [port (or (read-port)
                 (throw (ex-info "nREPL port not found" {:reason :nrepl-port-not-found})))
        res  (nrepl/eval-expr {:port port :expr form-str})]
    ;; babashka.nrepl-client returns {:value ... :err ... :out ... :ex ...}
    res))

(defn- cljs-eval
  "Evaluate a ClojureScript form in the connected browser runtime via
   shadow-cljs's `cljs-eval` API. Returns the raw nREPL response."
  [build-id form-str]
  (let [wrapped (format "(shadow.cljs.devtools.api/cljs-eval %s %s {})"
                        (pr-str build-id)
                        (pr-str form-str))]
    (jvm-eval wrapped)))

(defn- cljs-eval-value
  "Like cljs-eval but returns the parsed :value (edn), or throws with a
   structured reason if the eval errored."
  [build-id form-str]
  (let [res (cljs-eval build-id form-str)]
    (cond
      (some? (:ex res))
      (throw (ex-info "nREPL eval error" {:reason :eval-error
                                          :ex (:ex res)
                                          :err (:err res)}))
      (str/blank? (str (:value res)))
      nil
      :else
      (try
        (edn/read-string (str (:value res)))
        (catch Exception _
          ;; shadow-cljs's cljs-eval may return a wrapping map whose
          ;; :results key holds the actual value as a string.
          (str (:value res)))))))

;; ---------------------------------------------------------------------------
;; Subcommand: discover
;; ---------------------------------------------------------------------------

(defn- ensure-port! []
  (or (read-port)
      (die :nrepl-port-not-found
           :hint "Start your shadow-cljs dev build (`shadow-cljs watch <build>`).")))

(defn- build-id-from-args [args]
  (or (some-> (some #(when (str/starts-with? % "--build=") %) args)
              (str/replace-first "--build=" "")
              keyword)
      default-build-id))

(defn- inject-runtime!
  "Read scripts/runtime.cljs and cljs-eval it in the connected runtime.
   Returns the health map from `re-frame-pair.runtime/health`."
  [build-id]
  (let [source (slurp (runtime-cljs-path))]
    ;; Ship as a single (do ...) block so all forms execute atomically.
    (cljs-eval build-id source)
    ;; Then call health to confirm.
    (cljs-eval-value build-id "(re-frame-pair.runtime/health)")))

(defn- discover [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)]
    (try
      (let [health (inject-runtime! build-id)]
        (cond
          (not (:ok? health))
          (emit health)

          (not (:ten-x-loaded? health))
          (emit {:ok? false :reason :ns-not-loaded :missing :re-frame-10x
                 :hint "Add re-frame-10x to your dev deps and preloads."})

          (not (:trace-enabled? health))
          (emit {:ok? false :reason :trace-enabled-false
                 :hint "Set re-frame.trace.trace-enabled? to true via :closure-defines."})

          (not (:re-com-debug? health))
          (emit (merge health
                       {:ok? true
                        :warning :re-com-debug-disabled
                        :note "DOM ↔ source ops will degrade; otherwise functional."}))

          :else
          (emit (assoc health :ok? true :build-id build-id))))
      (catch Exception e
        (emit {:ok? false
               :reason (or (:reason (ex-data e)) :unknown)
               :message (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: eval
;; ---------------------------------------------------------------------------

(defn- eval-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-form :hint "usage: eval '<form>' [--build :app]"))
  (let [form     (first args)
        build-id (build-id-from-args (rest args))]
    (try
      (emit {:ok? true :value (cljs-eval-value build-id form)})
      (catch Exception e
        (emit {:ok? false
               :reason (or (:reason (ex-data e)) :eval-error)
               :message (.getMessage e)
               :data (dissoc (ex-data e) :reason)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: inject
;; ---------------------------------------------------------------------------

(defn- inject-op [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)]
    (try
      (emit (assoc (inject-runtime! build-id) :build-id build-id))
      (catch Exception e
        (emit {:ok? false :reason :inject-failed :message (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: dispatch
;; ---------------------------------------------------------------------------

(defn- has-flag? [args flag]
  (boolean (some #(= % flag) args)))

(defn- dispatch-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-event :hint "usage: dispatch '[:ev/id args...]' [--sync] [--trace]"))
  (let [event-str (first args)
        rest-args (rest args)
        build-id  (build-id-from-args rest-args)
        sync?     (has-flag? rest-args "--sync")
        trace?    (has-flag? rest-args "--trace")]
    (try
      (let [form (cond
                   trace? (format "(re-frame-pair.runtime/dispatch-and-collect %s)" event-str)
                   sync?  (format "(re-frame-pair.runtime/tagged-dispatch-sync! %s)" event-str)
                   :else  (format "(re-frame-pair.runtime/tagged-dispatch! %s)" event-str))
            value (cljs-eval-value build-id form)]
        ;; For --trace, the runtime returns a Promise. babashka.nrepl-client
        ;; will return the JS-object representation. Mark the result.
        (emit (cond
                trace? (merge {:ok? true :mode :trace}
                              (if (map? value) value {:value value}))
                sync?  (merge {:ok? true :mode :sync} value)
                :else  (merge {:ok? true :mode :queued} value))))
      (catch Exception e
        (emit {:ok? false :reason :dispatch-failed :message (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: trace-recent
;; ---------------------------------------------------------------------------

(defn- trace-recent-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-window :hint "usage: trace-recent <ms>"))
  (let [ms       (Integer/parseInt (first args))
        build-id (build-id-from-args (rest args))]
    (try
      (let [epochs (cljs-eval-value build-id
                                    (format "(re-frame-pair.runtime/epochs-in-last-ms %d)" ms))]
        (emit {:ok? true :window-ms ms :count (count epochs) :epochs epochs}))
      (catch Exception e
        (emit {:ok? false :reason :trace-failed :message (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: watch (pull-mode)
;; ---------------------------------------------------------------------------

(defn- parse-predicate-args [args]
  (loop [[a & more] args pred {}]
    (cond
      (nil? a) pred
      (= a "--event-id")        (recur (rest more) (assoc pred :event-id (keyword (first more))))
      (= a "--event-id-prefix") (recur (rest more) (assoc pred :event-id-prefix (keyword (first more))))
      (= a "--effects")         (recur (rest more) (assoc pred :effects (keyword (first more))))
      (= a "--timing-ms")       (recur (rest more) (let [[op-str n-str] (re-seq #"[<>]|\d+" (first more))]
                                                     (assoc pred :timing-ms
                                                            [(case op-str ">" :> "<" :< :>)
                                                             (Integer/parseInt n-str)])))
      (= a "--touches-path")    (recur (rest more) (assoc pred :touches-path
                                                           (edn/read-string (first more))))
      (= a "--sub-ran")         (recur (rest more) (assoc pred :sub-ran (keyword (first more))))
      (= a "--render")          (recur (rest more) (assoc pred :render (first more)))
      :else                     (recur more pred))))

(defn- flag-value
  "Return the value of a `--flag X` pair, or default."
  [args flag default]
  (if-let [idx (->> args (keep-indexed (fn [i v] (when (= v flag) i))) first)]
    (nth args (inc idx) default)
    default))

(defn- watch-op [args]
  (ensure-port!)
  (let [build-id   (build-id-from-args args)
        stream?    (has-flag? args "--stream")
        stop?      (has-flag? args "--stop")
        window-ms  (Long/parseLong (flag-value args "--window-ms" "30000"))
        count-n    (Long/parseLong (flag-value args "--count" "5"))
        pred       (parse-predicate-args args)
        idle-ms    (Long/parseLong (flag-value args "--idle-ms" "30000"))
        hard-ms    (Long/parseLong (flag-value args "--hard-ms" "300000"))
        poll-ms    (Long/parseLong (flag-value args "--poll-ms" "100"))]
    (cond
      stop?
      (emit {:ok? true :stopped? true
             :note "watch/stop is a no-op in pull-mode; simply terminate the running watch shell."})

      :else
      (try
        (let [pred-form (pr-str pred)
              start     (System/currentTimeMillis)
              last-id   (atom (cljs-eval-value build-id "(re-frame-pair.runtime/latest-epoch-id)"))
              emitted   (atom 0)
              last-hit  (atom (System/currentTimeMillis))
              done?     (fn []
                          (let [now      (System/currentTimeMillis)
                                elapsed  (- now start)
                                idle     (- now @last-hit)]
                            (cond
                              (and (not stream?) (>= elapsed window-ms))     [:done :window]
                              (and (not stream?) (>= @emitted count-n))      [:done :count]
                              (>= elapsed hard-ms)                           [:done :hard-cap]
                              (and stream? (>= idle idle-ms))                [:done :idle]
                              :else nil)))]
          (loop []
            (Thread/sleep poll-ms)
            (let [since-form (format "(mapv re-frame-pair.runtime/coerce-epoch (re-frame-pair.runtime/epochs-since %s))"
                                     (pr-str @last-id))
                  new-epochs (try (cljs-eval-value build-id since-form) (catch Exception _ []))
                  matches    (filterv (fn [e]
                                        (boolean
                                         (cljs-eval-value
                                          build-id
                                          (format "(re-frame-pair.runtime/epoch-matches? %s %s)"
                                                  pred-form (pr-str e)))))
                                      new-epochs)]
              (when (seq new-epochs)
                (reset! last-id (:id (last new-epochs))))
              (doseq [m matches]
                (swap! emitted inc)
                (reset! last-hit (System/currentTimeMillis))
                (emit {:ok? true :epoch m}))
              (if-let [[_ why] (done?)]
                (emit {:ok? true :finished? true :reason why :emitted @emitted})
                (recur)))))
        (catch Exception e
          (emit {:ok? false :reason :watch-failed :message (.getMessage e)}))))))

;; ---------------------------------------------------------------------------
;; Subcommand: tail-build (hot-reload/wait)
;; ---------------------------------------------------------------------------

(defn- tail-build-op [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)
        wait-ms  (Long/parseLong (flag-value args "--wait-ms" "5000"))
        probe    (flag-value args "--probe" nil)
        poll-ms  100]
    (cond
      (nil? probe)
      ;; Soft / timer-based fallback: no probe = wait a fixed delay
      ;; and report :soft? true per spec §4.5.
      (do (Thread/sleep 300)
          (emit {:ok? true :t (System/currentTimeMillis) :soft? true
                 :note "No probe supplied; waited a 300ms fixed delay."}))

      :else
      (let [before (try (cljs-eval-value build-id probe) (catch Exception _ ::error))
            start  (System/currentTimeMillis)]
        (loop []
          (Thread/sleep poll-ms)
          (let [elapsed (- (System/currentTimeMillis) start)
                now     (try (cljs-eval-value build-id probe) (catch Exception _ ::error))]
            (cond
              (and (not= now ::error) (not= now before))
              (emit {:ok? true :t (System/currentTimeMillis) :soft? false})

              (>= elapsed wait-ms)
              (emit {:ok? false :reason :timed-out :timed-out? true
                     :note "Probe did not change within --wait-ms. Likely a compile error; check your dev build output."})

              :else
              (recur))))))))

;; ---------------------------------------------------------------------------
;; Dispatcher
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (case (first args)
    "discover"     (discover (rest args))
    "eval"         (eval-op (rest args))
    "inject"       (inject-op (rest args))
    "dispatch"     (dispatch-op (rest args))
    "trace-recent" (trace-recent-op (rest args))
    "watch"        (watch-op (rest args))
    "tail-build"   (tail-build-op (rest args))
    (die :unknown-subcommand :arg (first args)
         :valid #{"discover" "eval" "inject" "dispatch" "trace-recent" "watch" "tail-build"})))

(apply -main *command-line-args*)
