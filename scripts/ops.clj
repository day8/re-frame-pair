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

(defn- read-port-candidates
  "Enumerate all plausibly-active shadow-cljs nREPL ports.

   The `SHADOW_CLJS_NREPL_PORT` env override is treated as the
   operator's explicit choice and suppresses file probing — when it's
   set, only the env port is reported.

   Otherwise: every existing candidate file with a parseable port.
   Returns a (possibly empty) vec of `{:port int :path str-or-:env}`.

   Used by `discover-list` and by `discover` itself to detect the
   multi-build / multi-port case so we can surface a warning instead
   of silently picking the first match."
  []
  (if-let [env-port (System/getenv "SHADOW_CLJS_NREPL_PORT")]
    [{:port (Integer/parseInt env-port) :path :env}]
    ;; De-dupe by port: a single shadow-cljs JVM can leave its
    ;; nrepl.port file in more than one of the candidate locations.
    ;; Keep the first path that resolved each unique port.
    (->> (for [path port-file-candidates
               :let [f (io/file path)]
               :when (.exists f)
               :let [text (str/trim (slurp f))]
               :when (re-matches #"\d+" text)]
           {:port (Integer/parseInt text) :path path})
         (reduce (fn [acc {:keys [port] :as cand}]
                   (if (some #(= port (:port %)) acc)
                     acc
                     (conj acc cand)))
                 [])
         vec)))

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

(defn- build-id-from-args
  "Extract `--build=<id>` from `args` and return it as a keyword.
   Accepts both `--build=app` and `--build=:app` (the latter is what
   discover-app.sh's usage line documents). The leading colon on the
   second form would otherwise survive into the keyword name —
   `(keyword \":app\")` produces a keyword whose NAME is the literal
   string \":app\", not the regular `:app` — so explicit build
   selection silently missed the right build (rfp-jfp)."
  [args]
  (or (some-> (some #(when (str/starts-with? % "--build=") %) args)
              (str/replace-first "--build=" "")
              ((fn [s] (if (str/starts-with? s ":") (subs s 1) s)))
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

;; ---------------------------------------------------------------------------
;; Inject mechanics — failure detection + chunked source-ship
;; ---------------------------------------------------------------------------
;;
;; nREPL's transport buffer caps a single eval at ~64KB. Sending the whole
;; runtime.cljs (currently ~67KB) as one cljs-eval call silently fails:
;; the JVM-side reader chokes mid-form with a CompilerException, the
;; response carries :ex / :err — and the OLD inject-path discarded the
;; response, reporting success. Defns past the truncation boundary never
;; landed; downstream ops returned nil. This bit rfp-r5s: app-summary
;; and handler-source weren't reachable at all on the live fixture.
;;
;; Two changes here, working together:
;;   1. `inject-failure` inspects the cljs-eval response shape and
;;      classifies transport / compile failures so callers (`ensure-
;;      injected!`, `inject-op`) can surface them as structured errors
;;      instead of pretending success.
;;   2. `chunked-inject!` splits the source at top-level form boundaries
;;      and sends each chunk separately, well under the buffer ceiling.
;;      Each chunk re-asserts `(ns re-frame-pair.runtime ...)` so vars
;;      land in the right namespace regardless of cljs-eval's per-call
;;      ns reset.

(defn- inject-failure
  "Inspect a cljs-eval response and return a structured failure map
   if the inject didn't complete cleanly, or nil if it succeeded.

   :ex set                  → JVM-side compile / read failure.
   :err matches Syntax / CompilerException → ditto, just no ex object.
   :value present + a known-bad sentinel inside → honor that.
   anything else            → assume success."
  [resp]
  (let [ex   (:ex resp)
        err  (str (:err resp))]
    (cond
      ex
      {:reason :inject-failed
       :stage  :transport
       :ex     ex
       :err    (when (seq err) err)
       :hint   "Source did not parse on the JVM side. Most common cause: file too large for the nREPL transport buffer (~64KB). With chunked-inject this should be unreachable; if you see it, the chunker may have produced an unbalanced split."}

      (re-find #"Syntax error|CompilerException|FileNotFoundException" err)
      {:reason :inject-failed
       :stage  :compile
       :err    err
       :hint   "Source did not compile in the connected runtime."}

      :else nil)))

(require '[clojure.tools.reader :as tr]
         '[clojure.tools.reader.reader-types :as trt])

(defn- form-end-offsets
  "Read `src` as a sequence of top-level CLJS forms and return the
   end character-offset of each one. tools.reader handles `#js`,
   regex literals, char literals, ::keywords, comments, and reader
   conditionals via the `:cljs` feature; the homegrown char-walker
   we started with would have to re-implement all of that.

   Returns a vec of int offsets (each is the index of the FIRST char
   AFTER that form's last char, suitable for `subs`)."
  [src]
  (let [;; Pre-compute char offsets of each line start so we can
        ;; convert the reader's (line, col) → absolute offset.
        line-starts (let [n (count src)]
                      (loop [i 0, acc [0]]
                        (if (>= i n) acc
                            (if (= \newline (.charAt ^String src i))
                              (recur (inc i) (conj acc (inc i)))
                              (recur (inc i) acc)))))
        lc->off  (fn [line col] (+ (nth line-starts (dec line)) (dec col)))
        reader   (trt/indexing-push-back-reader src)
        ignore   (fn [v] v)]
    (binding [tr/*data-readers* {'js ignore 'queue ignore 'inst ignore 'uuid ignore}]
      (loop [offs []]
        (let [form (tr/read {:eof :END :read-cond :allow :features #{:cljs}}
                            reader)]
          (if (= form :END)
            offs
            (recur (conj offs (lc->off (trt/get-line-number reader)
                                       (trt/get-column-number reader))))))))))

(defn- chunk-source
  "Split `src` into N strings, each a self-contained CLJS file
   re-asserting the ns and containing as many top-level forms as fit
   under `max-bytes`. The first chunk is the verbatim leading slice;
   subsequent chunks are `<ns-form>\\n<forms-slice>`.

   Each cljs-eval call starts fresh in cljs.user — re-asserting the
   ns at the top of every chunk is what makes the defns in chunks 2+
   land in re-frame-pair.runtime instead of cljs.user."
  [src max-bytes]
  (let [ends            (form-end-offsets src)
        ns-end          (first ends)
        ns-text         (subs src 0 ns-end)
        ns-prefix-bytes (inc ns-end) ;; ns-text + "\n"
        ranges (loop [i 0, chunk-start 0, last-end 0, ranges []]
                 (cond
                   (>= i (count ends))
                   (if (> (count src) chunk-start)
                     (conj ranges [chunk-start (count src)])
                     ranges)

                   :else
                   (let [end          (nth ends i)
                         first-chunk? (zero? (count ranges))
                         prefix-bytes (if first-chunk? 0 ns-prefix-bytes)
                         tentative    (+ prefix-bytes (- end chunk-start))]
                     (if (and (> tentative max-bytes)
                              (> last-end chunk-start))
                       ;; Flush current chunk at last-end; reconsider this
                       ;; form in the next chunk.
                       (recur i last-end last-end (conj ranges [chunk-start last-end]))
                       (recur (inc i) chunk-start end ranges)))))]
    (mapv (fn [[a b]]
            (let [body (subs src a b)]
              (if (zero? a)
                body
                (str ns-text "\n" body))))
          ranges)))

(def ^:private inject-chunk-bytes
  "Per-chunk size ceiling for the inject path. Well under the ~64KB
   nREPL transport buffer so we have head-room for the
   `(shadow.cljs.devtools.api/cljs-eval ...)` wrapping."
  32000)

(defn- chunked-inject!
  "Source-ship `runtime.cljs` to the connected runtime in size-bounded
   chunks. Returns a vec of `[chunk-index resp]` for each chunk so
   callers can detect partial failures."
  [build-id src]
  (let [chunks (chunk-source src inject-chunk-bytes)]
    (loop [i 0, results []]
      (if (>= i (count chunks))
        results
        (let [resp (cljs-eval build-id (nth chunks i))
              fail (inject-failure resp)]
          (if fail
            (conj results [i (assoc fail :chunk-index i :chunk-count (count chunks))])
            (recur (inc i) (conj results [i resp]))))))))

(defn- ensure-injected!
  "Idempotently ensure scripts/runtime.cljs is loaded in the connected
   browser runtime. Returns true if it had to (re-)ship the source,
   false if the runtime was already present.

   Source-ship is chunked: the CLJS file gets split at top-level form
   boundaries and shipped in pieces sized well under nREPL's transport
   buffer. Each chunk re-asserts the ns. If any chunk fails, dies with
   a structured `:inject-failed` response carrying the offending
   chunk index, error, and a hint.

   When a re-ship happens, follows up with one `health` call to install
   the native epoch / trace callbacks, the console-capture wrapper, and
   the last-click capture listener. Without this follow-up, the runtime
   ns lands but its capture cbs are never invoked — every post-refresh
   op that reads `native-epochs` / `native-traces` returns empty, and
   `console-tail` stops receiving entries. The four installs are guarded
   by idempotency atoms / window markers, so calling health a second
   time (e.g. through `inject-runtime!` for `discover`) is a no-op for
   side effects."
  [build-id]
  (if (runtime-already-injected? build-id)
    false
    (let [results (chunked-inject! build-id (slurp (runtime-cljs-path)))
          last-result (last results)]
      (if (and last-result (:reason (second last-result)))
        (let [[idx fail] last-result]
          (die :inject-failed
               :stage         (:stage fail)
               :chunk-index   idx
               :chunk-count   (:chunk-count fail)
               :ex            (:ex fail)
               :err           (:err fail)
               :hint          (:hint fail)))
        (do
          (cljs-eval-value build-id "(re-frame-pair.runtime/health)")
          true)))))

(defn- inject-runtime!
  "Ensure scripts/runtime.cljs is loaded and return the health map.
   Used by `discover` for its full health report."
  [build-id]
  (ensure-injected! build-id)
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

;; Forward reference: list-builds-on-port is defined further down with
;; the rest of the discover-list machinery. Babashka/sci does no
;; forward-symbol resolution at analysis time, so without this declare
;; loading ops.clj throws "Unable to resolve symbol" and every shim
;; breaks (rfp-xhx).
(declare list-builds-on-port)

(defn- discover [args]
  (ensure-port!)
  (let [build-id        (build-id-from-args args)
        ;; If the operator named a build (--build= or env), we don't
        ;; warn about multi-build — they made the choice.
        explicit-build? (or (some #(str/starts-with? % "--build=") args)
                            (boolean (System/getenv "SHADOW_CLJS_BUILD_ID")))]
    (try
      (let [health      (inject-runtime! build-id)
            version-err (version-failure health)
            ;; Multi-build awareness: probe active builds on the
            ;; chosen port. Failure is non-fatal — `discover` still
            ;; works, just without the warning.
            builds      (try (list-builds-on-port (read-port))
                             (catch Exception _ nil))
            multi?      (and (sequential? builds)
                             (> (count builds) 1)
                             (not explicit-build?))]
        (when multi?
          (binding [*out* *err*]
            (println (format "WARN: multiple shadow-cljs builds active on this nREPL port (%s); picked %s. Pass --build=<id> or set SHADOW_CLJS_BUILD_ID to choose explicitly. Run `scripts/discover-app.sh --list` to inspect all candidates."
                             (str/join ", " (map #(str %) builds))
                             build-id))
            (flush)))
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

          :else
          (emit (cond-> health
                  true                          (assoc :ok? true :build-id build-id)
                  (not (:re-com-debug? health)) (assoc :warning :re-com-debug-disabled
                                                       :note    "DOM ↔ source ops will degrade; otherwise functional.")
                  ;; Multi-build wins as the structured :warning when
                  ;; both apply — it's likely the cause of any other
                  ;; surprises (wrong build picked).
                  multi?                        (assoc :warning :multiple-builds
                                                       :picked  build-id
                                                       :others  (vec (remove #(= % build-id) builds)))))))
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
  (let [form        (first args)
        build-id    (build-id-from-args (rest args))
        reinjected? (ensure-injected! build-id)]
    (try
      (emit (cond-> {:ok? true :value (cljs-eval-value build-id form)}
              reinjected? (assoc :reinjected? true)))
      (catch Exception e
        (emit (cond-> {:ok? false
                       :reason (or (:reason (ex-data e)) :eval-error)
                       :message (.getMessage e)
                       :data (dissoc (ex-data e) :reason)}
                reinjected? (assoc :reinjected? true)))))))

;; ---------------------------------------------------------------------------
;; Subcommand: inject
;; ---------------------------------------------------------------------------

(defn- inject-op [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)]
    ;; Two failure modes worth distinguishing:
    ;;   :inject-failed — the source-ship eval threw (compile error,
    ;;                    nREPL transport problem). Health was never
    ;;                    attempted; the runtime is unchanged or
    ;;                    partially loaded.
    ;;   :health-failed — source shipped fine, but the post-inject
    ;;                    health read threw. The runtime is probably
    ;;                    in place but its `health` fn errored or
    ;;                    something downstream broke.
    ;; Lumping them as :inject-failed (the previous behavior) made
    ;; debugging hard — operator couldn't tell whether to re-edit
    ;; runtime.cljs or look elsewhere.
    (let [results (try
                    (chunked-inject! build-id (slurp (runtime-cljs-path)))
                    (catch Exception e
                      (emit {:ok? false :reason :inject-failed
                             :stage :transport-throw
                             :message (.getMessage e)})
                      ::failed))
          shipped? (and (not= results ::failed)
                        (let [last-result (last results)
                              fail (when last-result
                                     (let [[_ resp-or-fail] last-result]
                                       (when (:reason resp-or-fail) resp-or-fail)))]
                          (if fail
                            (do (emit (assoc fail
                                             :ok? false
                                             :chunk-count (count results)))
                                false)
                            true)))]
      (when shipped?
        (try
          (emit (assoc (cljs-eval-value build-id "(re-frame-pair.runtime/health)")
                       :build-id build-id
                       :forced? true
                       :chunk-count (count results)
                       :note "Source re-shipped via chunked inject. Use after editing scripts/runtime.cljs."))
          (catch Exception e
            (emit {:ok? false :reason :health-failed :message (.getMessage e)
                   :hint "Source shipped, but the post-inject `health` read threw. Try `scripts/discover-app.sh` for a fresh check."})))))))

;; ---------------------------------------------------------------------------
;; Subcommand: dispatch
;; ---------------------------------------------------------------------------

(defn- has-flag? [args flag]
  (boolean (some #(= % flag) args)))

(defn- partition-dispatch-args
  "Split dispatch.sh args into [event-str other-args]. The event-str is
   the first arg starting with `[` (the edn event-vec); everything else
   is treated as a flag. Order-independent so

     dispatch.sh '[:ev]' --trace
     dispatch.sh --trace '[:ev]'
     dispatch.sh --trace '[:ev]' --build=app

   all parse identically."
  [args]
  (let [{events true others false} (group-by #(str/starts-with? % "[") args)]
    [(first events) (concat others (rest events))]))

(defn- collect-flag-values
  "Return every value following an exact `flag` token. e.g.
   `(collect-flag-values [\"--stub\" \":http-xhrio\" \"--stub\" \":navigate\"]
                          \"--stub\")` → `[\":http-xhrio\" \":navigate\"]`.

   Skips trailing `--stub` with no value (forgiving — surfaces nothing
   rather than crashing). Used for repeatable flags like `--stub`."
  [args flag]
  (loop [[a & more] args, acc []]
    (cond
      (nil? a)   acc
      (= a flag) (if-let [v (first more)]
                   (recur (rest more) (conj acc v))
                   acc)
      :else      (recur more acc))))

(defn- parse-stub-fx-ids
  "Parse `--stub :foo --stub :bar` (or `--stub foo`) into `[:foo :bar]`."
  [args]
  (mapv (fn [s]
          (if (str/starts-with? s ":")
            (keyword (subs s 1))
            (keyword s)))
        (collect-flag-values args "--stub")))

;; ---------------------------------------------------------------------------
;; Tunable timings — keep them named so it's obvious where each comes from
;; ---------------------------------------------------------------------------

(def ^:private legacy-trace-collect-wait-ms
  "Bash-side sleep between `tagged-dispatch-sync!` and
   `collect-after-dispatch` on the legacy --trace path (re-frame
   predates rf-4mr / dispatch-and-settle). Mirrors runtime.cljs's
   trace-debounce-settle-ms: re-frame.trace's ~50ms callback debounce
   + 1 render frame for :render traces to land. 80ms is a comfortable
   upper bound. Once every fixture is on a re-frame that ships
   dispatch-and-settle, the legacy branch (and this constant) can go."
  80)

(def ^:private settle-poll-ms
  "Cadence for the bash shim's `await-settle` poll loop while
   `dispatch-and-settle!` has not yet resolved. Each iteration is one
   nREPL roundtrip plus this sleep. Tuned against re-frame's default
   :settle-window-ms (100ms) + trace-debounce (~50ms): a typical
   no-cascade event resolves at ~150ms, hit on the third poll."
  50)

(def ^:private settle-poll-budget-ms
  "Outer wall-clock cap on the `await-settle` poll loop. Sized
   slightly above re-frame's default :timeout-ms (5000ms) so we still
   surface the inner :reason :timeout resolution rather than reporting
   :poll-timeout when the cascade is genuinely stuck."
  6000)

(def ^:private default-watch-poll-ms
  "Default watch-epochs.sh poll cadence (overridable with --poll-ms)."
  100)

(def ^:private default-watch-window-ms
  "Default watch window when --count not yet hit (overridable with --window-ms).
   Half-open from start-of-watch."
  30000)

(def ^:private default-watch-idle-ms
  "In streaming mode, terminate when no match has fired this many ms
   (overridable with --idle-ms)."
  30000)

(def ^:private default-watch-hard-cap-ms
  "Absolute upper bound on a watch invocation (overridable with --hard-ms).
   The 'never silently runs forever' invariant from spec §4.4."
  300000)

(def ^:private default-watch-count
  "Default match count to wait for before terminating
   (overridable with --count)."
  5)

(def ^:private default-tail-build-wait-ms
  "Default tail-build.sh probe wait window (overridable with --wait-ms).
   Hard cap on how long we'll wait for a probe to flip after an edit."
  5000)

(def ^:private tail-build-poll-ms
  "tail-build.sh probe poll cadence."
  100)

(def ^:private tail-build-soft-delay-ms
  "When --probe is omitted, tail-build.sh just sleeps this long and
   reports :soft? true (per spec §4.5 — best we can do without a probe)."
  300)

(defn- await-settle-loop
  "Poll `(re-frame-pair.runtime/await-settle <handle>)` until the
   runtime returns a settled record or the wall-clock budget elapses.
   Each iteration is one cljs-eval roundtrip + `settle-poll-ms` sleep
   when the runtime reports :pending?.

   Returns the settled record (a map with `:settled? true` plus the
   resolution fields) on success, or
   `{:ok? false :reason :poll-timeout :handle h :budget-ms n}` if the
   bash-side budget elapses before resolution."
  [build-id handle]
  (let [deadline (+ (System/currentTimeMillis) settle-poll-budget-ms)]
    (loop []
      (let [r (cljs-eval-value
                build-id
                (format "(re-frame-pair.runtime/await-settle %s)"
                        (pr-str handle)))]
        (cond
          (and (map? r) (:settled? r)) r
          (>= (System/currentTimeMillis) deadline)
          {:ok? false :reason :poll-timeout :handle handle
           :budget-ms settle-poll-budget-ms}
          :else
          (do (Thread/sleep settle-poll-ms)
              (recur)))))))

(defn- dispatch-op [args]
  (ensure-port!)
  (let [[event-str rest-args] (partition-dispatch-args args)]
    (when-not event-str
      (die :missing-event :hint "usage: dispatch '[:ev/id args...]' [--sync] [--trace] [--build=<id>]"))
    (let [build-id    (build-id-from-args rest-args)
          sync?       (has-flag? rest-args "--sync")
          trace?      (has-flag? rest-args "--trace")
          stub-fx-ids (parse-stub-fx-ids rest-args)
          reinjected? (ensure-injected! build-id)
          tag-reinj   (fn [m] (cond-> m reinjected? (assoc :reinjected? true)))]
      (try
        (cond
          ;; --trace: prefer rf-4mr's `dispatch-and-settle` — adaptive
          ;; quiet-period heuristic, awaits the full cascade of
          ;; `:fx [:dispatch ...]` children, not just the root epoch.
          ;; The Promise it returns can't round-trip cljs-eval, so the
          ;; runtime stores the resolution behind a handle and we poll.
          ;; --stub <fx-id> (rf-ge8) routes through the same path with
          ;; record-only stubs in dispatch-and-settle!'s :stub-fx-ids opt.
          ;;
          ;; Falls back to the legacy `tagged-dispatch-sync!` +
          ;; fixed-sleep + `collect-after-dispatch` flow when re-frame
          ;; predates rf-4mr (the Promise-machinery just isn't there).
          ;; Once every fixture is on a re-frame with dispatch-and-settle,
          ;; the legacy branch can go.
          trace?
          (let [opts-form (if (seq stub-fx-ids)
                            (pr-str {:stub-fx-ids stub-fx-ids})
                            "{}")
                start (cljs-eval-value
                        build-id
                        (format "(re-frame-pair.runtime/dispatch-and-settle! %s %s)"
                                event-str opts-form))]
            (cond
              (and (map? start) (:ok? start) (:handle start))
              (let [resolved (await-settle-loop build-id (:handle start))]
                (emit (tag-reinj (merge {:mode :trace}
                                        (dissoc start :handle :pending?)
                                        resolved))))

              (and (map? start) (= :dispatch-and-settle-unavailable (:reason start)))
              ;; --stub on legacy re-frame: route through dispatch-sync-with-stubs! so the override map plants on event meta — otherwise the flag is silently dropped and the real fx fires.
              (let [sync-result (cljs-eval-value
                                  build-id
                                  (if (seq stub-fx-ids)
                                    (format "(re-frame-pair.runtime/dispatch-sync-with-stubs! %s %s)"
                                            event-str (pr-str stub-fx-ids))
                                    (format "(re-frame-pair.runtime/tagged-dispatch-sync! %s)"
                                            event-str)))]
                (if (and (map? sync-result) (:ok? sync-result))
                  (do
                    (Thread/sleep legacy-trace-collect-wait-ms)
                    (let [collected (cljs-eval-value
                                      build-id
                                      (format "(re-frame-pair.runtime/collect-after-dispatch %s)"
                                              (pr-str (:dispatch-id sync-result))))]
                      (emit (tag-reinj (merge {:mode :trace :legacy? true}
                                              sync-result collected)))))
                  (emit (tag-reinj (merge {:mode :trace :epoch nil :legacy? true}
                                          sync-result)))))

              :else
              (emit (tag-reinj (merge {:mode :trace :epoch nil} start)))))

          ;; --sync: dispatch-sync. With --stub, route through
          ;; dispatch-sync-with-stubs! (rf-ge8) for the same
          ;; effect-substitution semantics.
          sync?
          (emit (tag-reinj (merge {:mode :sync}
                                  (cljs-eval-value build-id
                                    (if (seq stub-fx-ids)
                                      (format "(re-frame-pair.runtime/dispatch-sync-with-stubs! %s %s)"
                                              event-str (pr-str stub-fx-ids))
                                      (format "(re-frame-pair.runtime/tagged-dispatch-sync! %s)"
                                              event-str))))))

          ;; queued: rf/dispatch. With --stub, dispatch-with-stubs!.
          :else
          (emit (tag-reinj (merge {:mode :queued}
                                  (cljs-eval-value build-id
                                    (if (seq stub-fx-ids)
                                      (format "(re-frame-pair.runtime/dispatch-with-stubs! %s %s)"
                                              event-str (pr-str stub-fx-ids))
                                      (format "(re-frame-pair.runtime/tagged-dispatch! %s)"
                                              event-str)))))))
        (catch Exception e
          (emit (tag-reinj {:ok? false :reason :dispatch-failed :message (.getMessage e)
                            :ex-data (ex-data e)})))))))

;; ---------------------------------------------------------------------------
;; Subcommand: trace-recent
;; ---------------------------------------------------------------------------

(defn- trace-recent-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-window :hint "usage: trace-recent <ms>"))
  (let [ms          (Integer/parseInt (first args))
        build-id    (build-id-from-args (rest args))
        reinjected? (ensure-injected! build-id)]
    (try
      (let [epochs (cljs-eval-value build-id
                                    (format "(re-frame-pair.runtime/epochs-in-last-ms %d)" ms))]
        (emit (cond-> {:ok? true :window-ms ms :count (count epochs) :epochs epochs}
                reinjected? (assoc :reinjected? true))))
      (catch Exception e
        (emit (cond-> {:ok? false :reason :trace-failed :message (.getMessage e)}
                reinjected? (assoc :reinjected? true)))))))

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
      ;; Anchored regex: exactly `<` or `>`, then digits, end. The
      ;; previous `re-seq #"[<>]|\d+"` silently accepted garbage like
      ;; `><100` or `100>5` because re-seq returned multiple matches
      ;; and we only inspected the first two.
      (let [raw (first more)
            m   (when raw (re-matches #"^([<>])(\d+)$" raw))
            [_ op-str n-str] m
            op  (case op-str ">" :> "<" :< nil)]
        (if (and op n-str)
          (recur (rest more)
                 (assoc pred :timing-ms [op (Integer/parseInt n-str)]))
          (die :bad-timing-ms
               :arg raw
               :hint "Expected e.g. '>100' or '<5'. Operators >= / <= / = and any other shape are not supported.")))
      (= a "--touches-path")    (recur (rest more) (assoc pred :touches-path
                                                           (edn/read-string (first more))))
      (= a "--sub-ran")         (recur (rest more) (assoc pred :sub-ran (->kw (first more))))
      (= a "--render")          (recur (rest more) (assoc pred :render (first more)))

      ;; --custom was reserved as an arbitrary CLJS predicate in earlier
      ;; spec drafts; v0.1 ships only the discrete keys above. `die`
      ;; emits the structured rejection AND exits 1 — the previous
      ;; (do (emit ...) (System/exit 1)) sequence broke the shell
      ;; contract because emit + exit-after-emit isn't idempotent
      ;; through pipes.
      (= a "--custom")
      (die :flag-not-supported
           :flag "--custom"
           :hint (str "Arbitrary CLJS predicate filters are deferred "
                      "to v0.2. v0.1 supports --event-id-prefix, "
                      "--effects, --timing-ms, --touches-path, "
                      "--sub-ran, --render."))

      :else                     (recur more pred))))

(defn- flag-value
  "Return the value of a `--flag X` pair, or default."
  [args flag default]
  (if-let [idx (->> args (keep-indexed (fn [i v] (when (= v flag) i))) first)]
    (nth args (inc idx) default)
    default))

(defn- watch-op [args]
  (ensure-port!)
  (let [build-id    (build-id-from-args args)
        stream?     (has-flag? args "--stream")
        stop?       (has-flag? args "--stop")
        window-ms   (Long/parseLong (flag-value args "--window-ms" (str default-watch-window-ms)))
        count-n     (Long/parseLong (flag-value args "--count" (str default-watch-count)))
        pred        (parse-predicate-args args)
        idle-ms     (Long/parseLong (flag-value args "--idle-ms" (str default-watch-idle-ms)))
        hard-ms     (Long/parseLong (flag-value args "--hard-ms" (str default-watch-hard-cap-ms)))
        poll-ms     (Long/parseLong (flag-value args "--poll-ms" (str default-watch-poll-ms)))
        ;; --dedupe-by :event suppresses consecutive duplicate emissions
        ;; whose :event vec matches the previous emitted one. Cribbed
        ;; from debux's :once option (docs/inspirations-debux.md §3c).
        ;; Only :event is accepted today; the flag shape leaves room for
        ;; future :event-id, :touches-path etc. without breaking the CLI.
        dedupe-by   (->kw (flag-value args "--dedupe-by" nil))
        reinjected? (when-not stop? (ensure-injected! build-id))]
    (cond
      stop?
      (emit {:ok? true :stopped? true
             :note "watch/stop is a no-op in pull-mode; simply terminate the running watch shell."})

      :else
      (try
        (let [start         (System/currentTimeMillis)
              last-id       (atom (cljs-eval-value build-id "(re-frame-pair.runtime/latest-epoch-id)"))
              emitted       (atom 0)
              last-emitted  (atom ::none) ;; nil is a real :event value, so use a sentinel
              last-hit      (atom (System/currentTimeMillis))
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
                ;; When --dedupe-by :event is set, skip an epoch whose
                ;; :event matches the last-emitted one. Useful with
                ;; --stream against handlers that fire many times in a
                ;; row (UI mash, polling, etc.).
                (let [key (case dedupe-by
                            :event (:event m)
                            nil)
                      dup? (and dedupe-by (= key @last-emitted))]
                  (when-not dup?
                    (when dedupe-by (reset! last-emitted key))
                    (swap! emitted inc)
                    (reset! last-hit (System/currentTimeMillis))
                    (emit {:ok? true :epoch m}))))
              (if-let [[_ why] (done?)]
                (emit (cond-> {:ok? true :finished? true :reason why :emitted @emitted}
                        reinjected? (assoc :reinjected? true)))
                (recur)))))
        (catch Exception e
          (emit (cond-> {:ok? false :reason :watch-failed :message (.getMessage e)}
                  reinjected? (assoc :reinjected? true))))))))

;; ---------------------------------------------------------------------------
;; Subcommand: tail-build (hot-reload/wait)
;; ---------------------------------------------------------------------------

(defn- tail-build-op [args]
  (ensure-port!)
  (let [build-id    (build-id-from-args args)
        wait-ms     (Long/parseLong (flag-value args "--wait-ms" (str default-tail-build-wait-ms)))
        probe       (flag-value args "--probe" nil)
        poll-ms     tail-build-poll-ms
        reinjected? (ensure-injected! build-id)]
    (cond
      (nil? probe)
      ;; Soft / timer-based fallback: no probe = wait a fixed delay
      ;; and report :soft? true per spec §4.5.
      (do (Thread/sleep tail-build-soft-delay-ms)
          (emit (cond-> {:ok? true :t (System/currentTimeMillis) :soft? true
                         :note (str "No probe supplied; waited a "
                                    tail-build-soft-delay-ms "ms fixed delay.")}
                  reinjected? (assoc :reinjected? true))))

      :else
      ;; Track the first error the probe ever produced so a "timeout"
      ;; that's actually a broken probe (compile error in the probe
      ;; form itself, undefined ns, etc.) reports the cause instead
      ;; of just "did not change". Without this, the operator chases
      ;; build output for a problem that's in the probe expression.
      (let [first-err   (atom nil)
            try-probe!  (fn []
                          (try (cljs-eval-value build-id probe)
                               (catch Exception e
                                 (when-not @first-err
                                   (reset! first-err (.getMessage e)))
                                 ::error)))
            before      (try-probe!)
            start       (System/currentTimeMillis)]
        (loop []
          (Thread/sleep poll-ms)
          (let [elapsed (- (System/currentTimeMillis) start)
                now     (try-probe!)]
            (cond
              (and (not= now ::error) (not= now before))
              (emit (cond-> {:ok? true :t (System/currentTimeMillis) :soft? false}
                      reinjected? (assoc :reinjected? true)))

              (>= elapsed wait-ms)
              (emit (cond-> {:ok? false :reason :timed-out :timed-out? true
                             :note "Probe did not change within --wait-ms. Likely a compile error in your dev build, OR a broken probe expression — see :probe-error if present."}
                      @first-err  (assoc :probe-error @first-err)
                      reinjected? (assoc :reinjected? true)))

              :else
              (recur))))))))

;; ---------------------------------------------------------------------------
;; Subcommand: discover-list (multi-build awareness)
;; ---------------------------------------------------------------------------
;;
;; `discover` picks the first nREPL port + the default build. In a
;; setup with two shadow-cljs builds (e.g. :app and :storybook
;; running side by side) the single match is silent, and the agent
;; can attach to the wrong build without realising. `discover-list`
;; surfaces ALL candidate ports + the active builds on each so the
;; operator can pick deliberately, and `discover` itself uses the
;; same query to attach a structured `:warning :multiple-builds`
;; when more than one build is active on the chosen port.

(defn- list-builds-on-port
  "Ask the shadow-cljs nREPL on `port` for the active build IDs via
   `(shadow.cljs.devtools.api/active-builds)`. Returns a sorted vec of
   keywords on success, nil on any failure (port not listening,
   shadow-cljs not loaded, parse failure).

   Note: shadow-cljs returns a SET (e.g. `#{:app}`), not a vector —
   accept both shapes (and any other coll? value, defensively) so a
   single-build setup doesn't read as 'no builds active' (rfp-j2i)."
  [port]
  (try
    (let [resp (combine-responses
                (nrepl-eval-raw port "(shadow.cljs.devtools.api/active-builds)"))
          v    (some-> resp :value safe-edn)]
      (cond
        (or (sequential? v) (set? v))
        (vec (sort-by str v))

        :else nil))
    (catch Exception _ nil)))

(defn- runtime-injected-on?
  "Best-effort check: is `re-frame-pair.runtime/session-id` bound
   for `build-id` on this specific `port`? Returns false on any
   failure (build not running, nREPL down, parse error)."
  [port build-id]
  (try
    (let [form (format "(shadow.cljs.devtools.api/cljs-eval %s %s {})"
                       (pr-str build-id)
                       (pr-str "re-frame-pair.runtime/session-id"))
          resp (combine-responses (nrepl-eval-raw port form))
          v    (some-> resp :value safe-edn)
          first-result (some-> v :results first safe-edn)]
      (boolean (and (string? first-result) (seq first-result))))
    (catch Exception _ false)))

(defn- discover-list []
  (let [candidates (read-port-candidates)]
    (mapv
     (fn [{:keys [port path]}]
       (let [builds (or (list-builds-on-port port) [])]
         {:port              port
          :path              (str path)
          :builds            builds
          ;; Reflects the default build's injection state on this port.
          ;; If default-build-id isn't running on this port we report
          ;; false rather than probing every build (cheap signal that
          ;; covers the common case).
          :runtime-injected? (boolean
                              (when (some #{default-build-id} builds)
                                (runtime-injected-on? port default-build-id)))}))
     candidates)))

(defn- discover-list-op [_args]
  (try
    (emit {:ok? true :default-build default-build-id :ports (discover-list)})
    (catch Exception e
      (emit {:ok? false :reason :discover-list-failed :message (.getMessage e)}))))

;; ---------------------------------------------------------------------------
;; Subcommand: handler-source
;; ---------------------------------------------------------------------------

(defn- handler-source-op [args]
  (ensure-port!)
  (when (< (count args) 2)
    (die :missing-args :hint "usage: handler-source :event :foo/bar"))
  (let [[kind id & more] args
        build-id    (build-id-from-args more)
        reinjected? (ensure-injected! build-id)
        ;; Accept :event or event for both args.
        kw          (fn [s]
                      (str ":" (if (str/starts-with? s ":") (subs s 1) s)))
        form        (format "(re-frame-pair.runtime/handler-source %s %s)"
                            (kw kind) (kw id))]
    (try
      (let [result (cljs-eval-value build-id form)]
        (emit (cond-> result
                reinjected? (assoc :reinjected? true))))
      (catch Exception e
        (emit (cond-> {:ok? false :reason :handler-source-failed :message (.getMessage e)}
                reinjected? (assoc :reinjected? true)))))))

;; ---------------------------------------------------------------------------
;; Subcommand: app-summary
;; ---------------------------------------------------------------------------

(defn- app-summary-op [args]
  (ensure-port!)
  (let [build-id    (build-id-from-args args)
        reinjected? (ensure-injected! build-id)]
    (try
      (let [result (cljs-eval-value build-id "(re-frame-pair.runtime/app-summary)")]
        (emit (cond-> result
                reinjected? (assoc :reinjected? true))))
      (catch Exception e
        (emit (cond-> {:ok? false :reason :app-summary-failed :message (.getMessage e)}
                reinjected? (assoc :reinjected? true)))))))

;; ---------------------------------------------------------------------------
;; Subcommand: console-tail
;; ---------------------------------------------------------------------------

(defn- console-tail-op [args]
  (ensure-port!)
  (let [since-id    (flag-value args "--since-id" "0")
        who         (flag-value args "--who" nil)
        build-id    (build-id-from-args args)
        reinjected? (ensure-injected! build-id)
        ;; Normalise --who: accept "app", ":app", "claude", ":claude", etc.
        who-kw      (when who
                      (str ":" (if (str/starts-with? who ":") (subs who 1) who)))
        form        (if who-kw
                      (format "(re-frame-pair.runtime/console-tail-since %s %s)"
                              since-id who-kw)
                      (format "(re-frame-pair.runtime/console-tail-since %s)"
                              since-id))]
    (try
      (let [result (cljs-eval-value build-id form)]
        (emit (cond-> result
                reinjected? (assoc :reinjected? true))))
      (catch Exception e
        (emit (cond-> {:ok? false :reason :console-tail-failed :message (.getMessage e)}
                reinjected? (assoc :reinjected? true)))))))

;; ---------------------------------------------------------------------------
;; Dispatcher
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (case (first args)
    "discover"       (if (some #{"--list"} (rest args))
                       (discover-list-op (rest args))
                       (discover (rest args)))
    "eval"         (eval-op (rest args))
    "inject"       (inject-op (rest args))
    "dispatch"     (dispatch-op (rest args))
    "trace-recent" (trace-recent-op (rest args))
    "watch"        (watch-op (rest args))
    "tail-build"   (tail-build-op (rest args))
    "console-tail"   (console-tail-op (rest args))
    "app-summary"    (app-summary-op (rest args))
    "handler-source" (handler-source-op (rest args))
    (die :unknown-subcommand :arg (first args)
         :valid #{"discover" "eval" "inject" "dispatch" "trace-recent" "watch" "tail-build" "console-tail" "app-summary" "handler-source"})))

;; Auto-run when invoked as a script. Tests load this file as a
;; library and set OPS_NO_AUTO_RUN to skip the dispatcher (otherwise
;; (case (first nil) ...) hits :else and System/exit 1's the runner).
(when-not (System/getenv "OPS_NO_AUTO_RUN")
  (apply -main *command-line-args*))
