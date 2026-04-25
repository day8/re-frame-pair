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
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net Socket)
           (java.io PushbackInputStream)))

;; ---------------------------------------------------------------------------
;; Minimal bencode + nREPL socket client
;; ---------------------------------------------------------------------------
;;
;; bb doesn't ship a built-in nREPL client and we don't want a classpath
;; dep just for this. Bencode is a 40-line protocol and nREPL speaks it
;; directly over TCP; inline is simpler than bolting on Maven deps.

(defn- bencode ^String [v]
  (cond
    (integer? v)   (str "i" v "e")
    (string? v)    (let [bs (.getBytes ^String v "UTF-8")]
                     (str (alength bs) ":" v))
    (keyword? v)   (bencode (name v))
    (map? v)       (str "d"
                        (apply str (mapcat (fn [[k v]] [(bencode k) (bencode v)])
                                           (sort-by (fn [[k _]] (if (keyword? k) (name k) (str k))) v)))
                        "e")
    (sequential? v) (str "l" (apply str (map bencode v)) "e")
    (nil? v)       (bencode "")
    :else          (bencode (pr-str v))))

(defn- read-char [^PushbackInputStream in]
  (let [b (.read in)]
    (when (neg? b) (throw (ex-info "unexpected EOF" {})))
    (char b)))

(defn- bdecode [^PushbackInputStream in]
  (let [c (read-char in)]
    (case c
      \i (let [sb (StringBuilder.)]
           (loop [ch (read-char in)]
             (if (= ch \e)
               (Long/parseLong (.toString sb))
               (do (.append sb ch) (recur (read-char in))))))
      \l (loop [acc []]
           (let [b (.read in)]
             (cond (neg? b)          (throw (ex-info "unexpected EOF in list" {}))
                   (= b (int \e))    acc
                   :else             (do (.unread in b) (recur (conj acc (bdecode in)))))))
      \d (loop [acc {}]
           (let [b (.read in)]
             (cond (neg? b)          (throw (ex-info "unexpected EOF in dict" {}))
                   (= b (int \e))    acc
                   :else             (do (.unread in b)
                                         (let [k (bdecode in)
                                               v (bdecode in)]
                                           (recur (assoc acc k v)))))))
      ;; digit — byte string of length N
      (let [sb (StringBuilder.)]
        (.append sb c)
        (loop [ch (read-char in)]
          (if (= ch \:)
            (let [len (Long/parseLong (.toString sb))
                  buf (byte-array len)]
              (loop [read 0]
                (when (< read len)
                  (let [n (.read in buf read (- len read))]
                    (when-not (pos? n) (throw (ex-info "EOF in string body" {})))
                    (recur (+ read n)))))
              (String. buf "UTF-8"))
            (do (.append sb ch) (recur (read-char in)))))))))

(defn- nrepl-eval-raw
  "Open a socket to nREPL at port, send an op eval of code-str, read
   responses until :status contains \"done\", close, return responses."
  [port code-str]
  (with-open [sock (Socket. "127.0.0.1" (int port))]
    (let [out (.getOutputStream sock)
          in  (PushbackInputStream. (.getInputStream sock))
          id  (str (random-uuid))
          msg (bencode {"op" "eval" "code" code-str "id" id})]
      (.write out (.getBytes ^String msg "UTF-8"))
      (.flush out)
      (loop [responses []]
        (let [resp (bdecode in)
              responses' (conj responses resp)
              done? (and (= id (get resp "id"))
                         (some #{"done"} (get resp "status" [])))]
          (if done?
            responses'
            (recur responses')))))))

(defn- combine-responses
  "Collapse a sequence of nREPL responses into a single result map with
   :value (last), :out (concatenated), :err, :ex, :status."
  [responses]
  (reduce (fn [acc r]
            (cond-> acc
              (contains? r "value") (assoc :value (get r "value"))
              (contains? r "out")   (update :out str (get r "out"))
              (contains? r "err")   (update :err str (get r "err"))
              (contains? r "ex")    (assoc :ex (get r "ex"))
              (contains? r "status") (update :status (fnil into #{}) (get r "status"))))
          {:out "" :err ""}
          responses))

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
  ;; Lives at scripts/re_frame_pair/runtime.cljs — canonical
  ;; ns-to-path layout for the `re-frame-pair.runtime` namespace, so
  ;; shadow-cljs can compile it for the runtime-test build alongside
  ;; the inject path.
  (.getPath (io/file (.getParent (io/file *file*)) "re_frame_pair" "runtime.cljs")))

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
  "Evaluate a Clojure (JVM-side) form over nREPL and return the combined
   response map: {:value :out :err :ex :status}."
  [form-str]
  (let [port (or (read-port)
                 (throw (ex-info "nREPL port not found" {:reason :nrepl-port-not-found})))]
    (combine-responses (nrepl-eval-raw port form-str))))

(defn- cljs-eval
  "Evaluate a ClojureScript form in the connected browser runtime via
   shadow-cljs's `cljs-eval` API. Returns the raw nREPL response."
  [build-id form-str]
  (let [wrapped (format "(shadow.cljs.devtools.api/cljs-eval %s %s {})"
                        (pr-str build-id)
                        (pr-str form-str))]
    (jvm-eval wrapped)))

(defn- safe-edn [s]
  (try (edn/read-string s) (catch Exception _ s)))

(defn- cljs-eval-value
  "Like cljs-eval but unwraps to the actual CLJS value.

   shadow-cljs's `cljs-eval` returns a JVM map shaped like
   `{:results [<printed-cljs-value-as-str>]}`. The nREPL response's
   :value is the *printed* form of that map, so we:
     1. edn/read the outer :value (JVM map);
     2. take the last element of :results (the evaluated CLJS form's
        printed value as a string);
     3. edn/read that string to recover the CLJS value."
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
      (let [outer (safe-edn (str (:value res)))]
        (cond
          ;; Shadow result shape.
          (and (map? outer) (vector? (:results outer)))
          (when-let [last-result (peek (:results outer))]
            (safe-edn last-result))

          ;; Newer shadow may return {:err "..."} on cljs errors.
          (and (map? outer) (:err outer))
          (throw (ex-info "cljs eval error"
                          {:reason :cljs-eval-error
                           :err (:err outer)}))

          ;; Fallback — assume we already have the value.
          :else outer)))))

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

(defn- runtime-already-injected?
  "Fast-path check: if the session sentinel var exists and resolves to
   a non-nil string, the runtime is already injected in this browser
   runtime and we can skip re-shipping scripts/runtime.cljs.

   Returns a boolean. Swallows evaluation errors (treating them as
   'not injected') because the eval *will* throw with an undefined-ns
   error until the first inject.

   Spec §3.4 — the sentinel is why re-injection only fires on full
   page refresh, not every connect."
  [build-id]
  (try
    (let [v (cljs-eval-value build-id "re-frame-pair.runtime/session-id")]
      (and (string? v) (seq v)))
    (catch Exception _ false)))

(defn- inject-runtime!
  "Ensure scripts/runtime.cljs is loaded in the connected runtime.

   Fast path: if the session sentinel already exists, skip the source
   ship and just call health. Slow path (first connect, or after a
   full page refresh): slurp and cljs-eval the whole file, then call
   health to confirm.

   Returns the health map from `re-frame-pair.runtime/health`."
  [build-id]
  (when-not (runtime-already-injected? build-id)
    (let [source (slurp (runtime-cljs-path))]
      ;; Ship as a single block so all forms execute atomically.
      (cljs-eval build-id source)))
  (cljs-eval-value build-id "(re-frame-pair.runtime/health)"))

(defn- version-failure
  "If the health report's version block names a below-floor dep,
   return the first offender as a structured error map. Otherwise nil."
  [health]
  (let [by-dep (get-in health [:versions :by-dep])]
    (some (fn [[dep {:keys [observed floor ok? enforced?]}]]
            (when (and enforced? (not ok?))
              {:ok? false
               :reason :version-too-old
               :dep dep
               :observed observed
               :required floor}))
          by-dep)))

(defn- discover [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)]
    (try
      (let [health      (inject-runtime! build-id)
            version-err (version-failure health)]
        (cond
          (not (:ok? health))
          (emit health)

          (not (:ten-x-loaded? health))
          (emit {:ok? false :reason :ns-not-loaded :missing :re-frame-10x
                 :hint "Add re-frame-10x to your dev deps and preloads."})

          (not (:trace-enabled? health))
          (emit {:ok? false :reason :trace-enabled-false
                 :hint "Set re-frame.trace.trace-enabled? to true via :closure-defines."})

          (some? version-err)
          (emit version-err)

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

(defn- inject-runtime-force!
  "Like inject-runtime! but *always* re-ships scripts/runtime.cljs
   regardless of whether the session sentinel already exists. Used by
   the standalone `scripts/inject-runtime.sh` so skill developers
   editing runtime.cljs can re-push their changes into a live browser
   without a full page refresh."
  [build-id]
  (let [source (slurp (runtime-cljs-path))]
    (cljs-eval build-id source))
  (cljs-eval-value build-id "(re-frame-pair.runtime/health)"))

(defn- inject-op [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)]
    (try
      (emit (assoc (inject-runtime-force! build-id)
                   :build-id build-id
                   :forced? true
                   :note "Source re-shipped regardless of sentinel. Use this after editing scripts/runtime.cljs."))
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
      (cond
        ;; --trace: sync dispatch; wait two animation frames bash-side
        ;; so Reagent's render traces land; then fetch the epoch by id.
        ;; The frame wait is bash-side (sleep) rather than runtime-side
        ;; (js/Promise) because Promise results don't survive the
        ;; cljs-eval round-trip cleanly.
        trace?
        (let [sync-result (cljs-eval-value
                            build-id
                            (format "(re-frame-pair.runtime/tagged-dispatch-sync! %s)" event-str))]
          (if (and (map? sync-result) (:ok? sync-result) (:epoch-id sync-result))
            (do
              (Thread/sleep 32)            ; ~2 animation frames at 60fps
              (let [epoch (cljs-eval-value
                            build-id
                            (format "(re-frame-pair.runtime/epoch-by-id %s)"
                                    (pr-str (:epoch-id sync-result))))]
                (emit (merge {:mode :trace} sync-result
                             (when epoch {:epoch epoch})))))
            (emit (merge {:mode :trace :epoch nil} sync-result))))

        sync?
        (emit (merge {:mode :sync}
                     (cljs-eval-value build-id
                       (format "(re-frame-pair.runtime/tagged-dispatch-sync! %s)" event-str))))

        :else
        (emit (merge {:mode :queued}
                     (cljs-eval-value build-id
                       (format "(re-frame-pair.runtime/tagged-dispatch! %s)" event-str)))))
      (catch Exception e
        (emit {:ok? false :reason :dispatch-failed :message (.getMessage e)
               :ex-data (ex-data e)})))))

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

(defn- ->kw
  "Coerce a CLI arg to a keyword. Accepts `:foo`, `foo`, or `foo/bar`."
  [s]
  (when s
    (if (str/starts-with? s ":")
      (keyword (subs s 1))
      (keyword s))))

(defn- parse-predicate-args
  "Build a predicate map from CLI args. The event-id-prefix is kept
   as a plain string so epoch-matches? can do substring matching on
   the printed form of the event keyword — avoids keyword lexer edge
   cases like `:cart/` (trailing slash)."
  [args]
  (loop [[a & more] args pred {}]
    (cond
      (nil? a) pred
      (= a "--event-id")        (recur (rest more) (assoc pred :event-id (->kw (first more))))
      (= a "--event-id-prefix") (recur (rest more)
                                       (let [raw (first more)]
                                         ;; Normalise: prepend ':' if absent, so the string
                                         ;; matches the printed form of event keywords.
                                         (assoc pred :event-id-prefix
                                                (if (str/starts-with? raw ":") raw (str ":" raw)))))
      (= a "--effects")         (recur (rest more) (assoc pred :effects (->kw (first more))))
      (= a "--timing-ms")
      (let [raw (first more)
            [op-str n-str] (re-seq #"[<>]|\d+" (or raw ""))
            op (case op-str ">" :> "<" :< nil)]
        (if (and op n-str)
          (recur (rest more)
                 (assoc pred :timing-ms [op (Integer/parseInt n-str)]))
          (do
            (emit {:ok? false :reason :bad-timing-ms
                   :arg raw
                   :hint "Expected e.g. '>100' or '<5'. Operators >= / <= / = are not supported."})
            (System/exit 1))))
      (= a "--touches-path")    (recur (rest more) (assoc pred :touches-path
                                                           (edn/read-string (first more))))
      (= a "--sub-ran")         (recur (rest more) (assoc pred :sub-ran (->kw (first more))))
      (= a "--render")          (recur (rest more) (assoc pred :render (first more)))

      ;; --custom is reserved in the spec as an arbitrary CLJS predicate,
      ;; but v1 does not implement it (would require shipping user forms
      ;; into the runtime). Fail loudly rather than silently dropping —
      ;; users seeing "no matches" with a --custom flag should know why.
      (= a "--custom")
      (do
        (emit {:ok? false
               :reason :not-yet-supported
               :flag :--custom
               :hint (str "Arbitrary CLJS predicate filters are reserved "
                          "but not yet implemented. Use --event-id-prefix, "
                          "--effects, --timing-ms, --touches-path, --sub-ran, "
                          "or --render instead.")})
        (System/exit 1))

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
        (let [start     (System/currentTimeMillis)
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
                              :else nil)))
              fetch-form (fn [since-id]
                           ;; One eval per poll: fetch new-epochs-since map,
                           ;; filter matches in-runtime, return both pieces
                           ;; so the CLI can see aged-out without a second
                           ;; round-trip.
                           (format
                            "(let [r (re-frame-pair.runtime/epochs-since %s)
                                   matches (filterv #(re-frame-pair.runtime/epoch-matches? %s %%) (:epochs r))]
                               {:matches matches
                                :id-aged-out? (:id-aged-out? r)
                                :head-id (re-frame-pair.runtime/latest-epoch-id)})"
                            (pr-str since-id)
                            (pr-str pred)))
              aged-warned? (atom false)]
          (loop []
            (Thread/sleep poll-ms)
            (let [result    (try (cljs-eval-value build-id (fetch-form @last-id))
                                 (catch Exception _ nil))
                  matches   (or (:matches result) [])
                  head-id   (:head-id result)
                  aged-out? (:id-aged-out? result)]
              (when head-id (reset! last-id head-id))
              (when (and aged-out? (not @aged-warned?))
                (reset! aged-warned? true)
                (emit {:ok? true :warning :id-aged-out
                       :note "The id we were tracking fell off 10x's ring buffer between polls — some matching epochs may have been missed."}))
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
