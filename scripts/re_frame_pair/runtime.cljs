;;;; re-frame-pair.runtime — injected helper namespace
;;;;
;;;; This file is evaluated by `scripts/inject-runtime.sh` on first
;;;; connect. It creates the `re-frame-pair.runtime` namespace inside
;;;; the running browser app and populates it with helpers that the
;;;; skill's ops call through `eval-cljs.sh`.
;;;;
;;;; Design invariants (see docs/initial-spec.md):
;;;;   - When re-frame core ships `register-epoch-cb` (rf-ybv,
;;;;     commit 4a53afb), runtime installs its own epoch-cb +
;;;;     trace-cb to consume assembled epochs and the trace stream
;;;;     directly. Falls back to reading 10x's epoch buffer when
;;;;     re-frame predates rf-ybv. (The native path retired the old
;;;;     "no second register-trace-cb" rule — that was load-bearing
;;;;     on 10x being the trace substrate, which native epoch-cb
;;;;     supersedes.)
;;;;   - The `session-id` sentinel below is re-read on every op. If
;;;;     it's gone, a full page refresh happened and the shim
;;;;     re-injects this file.
;;;;   - 10x internals accessed here are not a public API; the
;;;;     `day8.re-frame-10x.public` namespace covers most of what
;;;;     used to be inlined-rf walking, but a few legacy paths
;;;;     remain — flagged in-place where they live.
;;;;
;;;; This file is source-of-truth for injection. The shell shim reads
;;;; it and ships the forms over nREPL — so keep it self-contained.

(ns re-frame-pair.runtime
  (:require [clojure.data :as data]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.registrar :as rf-registrar]
            [re-frame.trace]
            [re-frame-pair.runtime.console :as console]
            [re-frame-pair.runtime.dom :as dom]
            [re-frame-pair.runtime.epochs :as epochs]
            [re-frame-pair.runtime.native-epoch :as native-epoch]
            [re-frame-pair.runtime.re-com :as re-com]
            [re-frame-pair.runtime.registrar :as registrar]
            [re-frame-pair.runtime.session :as session]
            [re-frame-pair.runtime.ten-x-adapter :as ten-x]
            [re-frame-pair.runtime.time-travel :as time-travel]
            [re-frame-pair.runtime.versions :as versions]))

;; ---------------------------------------------------------------------------
;; Session + app-db — re-exported from re-frame-pair.runtime.session
;; ---------------------------------------------------------------------------

(def session-id     session/session-id)
(def sentinel       session/sentinel)
(def snapshot       session/snapshot)
(def app-db-at      session/app-db-at)
(def app-db-reset!  session/app-db-reset!)
(def schema         session/schema)

;; ---------------------------------------------------------------------------
;; Registrar + subs — re-exported from re-frame-pair.runtime.registrar
;; ---------------------------------------------------------------------------

(def registrar-list         registrar/registrar-list)
(def registrar-describe     registrar/registrar-describe)
(def extract-query-vs       registrar/extract-query-vs)
(def subs-live              registrar/subs-live)
(def subs-sample            registrar/subs-sample)

;; ---------------------------------------------------------------------------
;; handler-source — re-exported from re-frame-pair.runtime.registrar
;; ---------------------------------------------------------------------------

(def handler-source         registrar/handler-source)

;; ---------------------------------------------------------------------------
;; re-frame-10x epoch buffer adapter — re-exported from
;; re-frame-pair.runtime.ten-x-adapter
;; ---------------------------------------------------------------------------

(def aget-path           ten-x/aget-path)
(def ten-x-public        ten-x/ten-x-public)
(def ten-x-app-db-ratom  ten-x/ten-x-app-db-ratom)
(def ten-x-rf-core       ten-x/ten-x-rf-core)
(def ten-x-loaded?       ten-x/ten-x-loaded?)
(def read-10x-all-traces ten-x/read-10x-all-traces)
(def read-10x-epochs     ten-x/read-10x-epochs)
(def match-id            ten-x/match-id)
(def find-trace          ten-x/find-trace)
(def coerce-epoch        ten-x/coerce-epoch)

;; ---------------------------------------------------------------------------
;; Native epoch path — re-exported from re-frame-pair.runtime.native-epoch
;; ---------------------------------------------------------------------------

(def native-epoch-buffer        native-epoch/native-epoch-buffer)
(def native-trace-buffer        native-epoch/native-trace-buffer)
(def native-epoch-cb-installed? native-epoch/native-epoch-cb-installed?)
(def native-trace-cb-installed? native-epoch/native-trace-cb-installed?)
(def receive-native-epochs!     native-epoch/receive-native-epochs!)
(def receive-native-traces!     native-epoch/receive-native-traces!)
(def register-trace-cb-fn       native-epoch/register-trace-cb-fn)
(def install-native-epoch-cb!   native-epoch/install-native-epoch-cb!)
(def install-native-trace-cb!   native-epoch/install-native-trace-cb!)
(def native-epochs              native-epoch/native-epochs)
(def native-traces              native-epoch/native-traces)
(def find-native-epoch-by-id    native-epoch/find-native-epoch-by-id)
(def coerce-native-epoch        native-epoch/coerce-native-epoch)

(defn debux-runtime-api?
  "True iff `day8.re-frame.tracing.runtime/runtime-api?` is loaded
   in this runtime — i.e. the on-demand instrumentation Phase 2
   API (wrap-handler! / unwrap-handler! / etc.) is available.
   False both when day8.re-frame/tracing isn't on the classpath at
   all AND when an older release is loaded that ships only the
   fn-traced macro (pre-rfd-8g9; runtime ns landed in re-frame-debux
   commit 4ed07c9, runtime-api? probe-var landed in commit 6b04e6b
   under ci-hpg / rf-yvu).

   Used by SKILL.md's 'Trace a handler / sub / fx form-by-form'
   recipe to dispatch between the wrap-handler!/unwrap-handler!
   path (preferred — clean unwrap, no source-eval round-trip) and
   the manual fn-traced AST-rewrite fallback.

   Probes the JS-side munged path
   `day8.re_frame.tracing.runtime.runtime_api_QMARK_` rather than
   CLJS `resolve` because that pattern doesn't require the
   namespace to be on the cljs source path at runtime.cljs compile
   time — the helper still works in builds that don't bundle debux.
   Switched from probing `wrap_handler_BANG_` to the dedicated
   `runtime_api_QMARK_` var (rf-yvu Phase 2): the upstream now owns
   the detection contract, so the symbol won't be renamed away in
   a refactor of the wrap/unwrap surface itself."
  []
  (boolean
    (when-let [g (some-> js/goog .-global)]
      (aget-path g ["day8" "re_frame" "tracing" "runtime" "runtime_api_QMARK_"]))))

(defn dbg-macro-available?
  "True iff re-frame-debux ships rfd-btn (`day8.re-frame.tracing/dbg`)
   in the currently-loaded build. False when day8.re-frame/tracing
   isn't on the classpath at all AND when an older release is loaded
   that ships only fn-traced / dbgn (pre-rfd-btn).

   Probes the runtime sink fn `send-trace-or-tap!` rather than the
   `dbg` macro itself because macros aren't reachable as JS symbols
   at runtime — the macro expands at compile time. `send-trace-or-tap!`
   is the runtime helper every dbg call funnels through, and it lands
   in the same commit as the macro (rfd-btn), so its presence is a
   reliable proxy.

   Used by SKILL.md's 'Trace a single expression at the REPL' recipe
   to surface dbg as an option only on debux releases that ship it."
  []
  (boolean
    (when-let [g (some-> js/goog .-global)]
      (aget-path g ["day8" "re_frame" "debux" "common" "util" "send_trace_or_tap_BANG_"]))))

;; ---------------------------------------------------------------------------
;; Epoch ladder — re-exported from re-frame-pair.runtime.epochs
;; ---------------------------------------------------------------------------

(def latest-epoch-id   epochs/latest-epoch-id)
(def epoch-count       epochs/epoch-count)
(def epoch-by-id       epochs/epoch-by-id)
(def last-epoch        epochs/last-epoch)
(def epochs-since      epochs/epochs-since)
(def epochs-in-last-ms epochs/epochs-in-last-ms)
(def find-where        epochs/find-where)
(def find-all-where    epochs/find-all-where)

;; ---------------------------------------------------------------------------
;; Console capture — re-exported from re-frame-pair.runtime.console
;; ---------------------------------------------------------------------------

(def console-log             console/console-log)
(def current-who             console/current-who)
(def append-console-entry!   console/append-console-entry!)
(def install-console-capture! console/install-console-capture!)
(def console-tail-since      console/console-tail-since)

;; ---------------------------------------------------------------------------
;; Claude-dispatch tagging — session-local set of upstream's
;; auto-generated :dispatch-ids for events we (vs. the user) fired.
;; Lets last-claude-epoch answer "the most recent epoch I dispatched"
;; without the caller threading the id back to us. Stores dispatch-ids
;; (UUIDs) rather than 10x match-ids.
;; ---------------------------------------------------------------------------

(defonce ^:private claude-dispatch-ids
  ;; FIFO ring-buffer of skill-driven :dispatch-ids. Bounded so a long
  ;; debug session — `defonce` survives the runtime re-inject that
  ;; fires when discover-app's sentinel goes missing on browser
  ;; refresh — doesn't accumulate UUIDs without limit. `:entries` is a
  ;; PersistentQueue for O(overflow) head-pop eviction; `:ids` is the
  ;; matching membership set kept lock-step so `last-claude-epoch`'s
  ;; lookup stays O(1) instead of growing linearly with session age.
  ;; `:total-count` is monotonic — surfaced via app-summary so the
  ;; lifetime "skill dispatched N events" view survives eviction.
  (atom {:entries     #queue []
         :ids         #{}
         :max-size    100
         :total-count 0}))

(defn- record-claude-dispatch-id!
  "Append `dispatch-id` to the bounded claude-dispatch-ids ring buffer.
   FIFO-evicts the oldest entry when :max-size is exceeded; both
   :entries and :ids stay in sync so the read path's `contains?` over
   :ids matches the queue's current contents."
  [dispatch-id]
  (swap! claude-dispatch-ids
         (fn [{:keys [entries ids max-size total-count]}]
           (let [q (conj entries dispatch-id)]
             (loop [q  q
                    is (conj ids dispatch-id)]
               (if (> (count q) max-size)
                 (recur (pop q) (disj is (peek q)))
                 {:entries     q
                  :ids         is
                  :max-size    max-size
                  :total-count (inc total-count)}))))))

(defn- traces-atom
  "JS-interop accessor for the `re-frame.trace/traces` atom (re-frame's
   internal trace ring). Routed through the same `goog.global` probe
   as the other re-frame.trace surfaces so the lookup degrades to nil
   if a future re-frame trace-ns refactor removes or relocates it."
  []
  (when-let [g (some-> js/goog .-global)]
    (aget-path g ["re_frame" "trace" "traces"])))

(defn- recent-dispatch-id
  "After a `dispatch-sync`, read `re-frame.trace/traces` for the
   most recent `:event` trace and return its `:dispatch-id`.
   `re-frame.trace/traces` is updated synchronously inside
   `re-frame.events/handle`'s `with-trace` finish-trace (the cb
   delivery to 10x runs through a ~50ms debounce, but the source
   atom updates immediately), so this resolves the id we just
   generated as long as no cb fired between finish-trace and our
   read — acceptable race in practice (single-threaded JS, the cb
   is goog.functions/debounce'd 50ms out from the LAST trace).

   nil when re-frame predates rf-3p7 commit af024c3 (no
   :dispatch-id tag generated), or when the trace atom isn't
   reachable on the loaded re-frame build."
  []
  (when-let [traces-ref (traces-atom)]
    (let [traces @traces-ref]
      (->> (if (vector? traces) (rseq traces) (reverse traces))
           (some (fn [t] (when (= :event (:op-type t))
                           (-> t :tags :dispatch-id))))))))

(defn tagged-dispatch!
  "Dispatch an event (queued) — the handler runs out of band, so the
   :dispatch-id is generated only when handle eventually fires.

   `current-who` is set to `:claude` for the duration of the enqueue
   call; the handler itself runs out of band, so handler-side console
   output tags `:app`. Use `tagged-dispatch-sync!` when you need
   handler output tagged.

   Returns:
     {:ok? true :queued? true :event ev :dispatch-id nil :epoch-id nil
      :note <string>}

   `:dispatch-id` and `:epoch-id` are structurally nil on the queued
   path — the handler runs out of band, so neither id is available at
   enqueue time, regardless of re-frame version. The `:note` makes the
   structural nil explicit so callers don't conflate it with the
   tagged-dispatch-sync! 'predates rf-3p7' nil. Use tagged-dispatch-sync!
   if you need :dispatch-id correlation back to an epoch."
  [event-v]
  (reset! current-who :claude)
  (try
    (rf/dispatch event-v)
    (finally
      (reset! current-who :app)))
  {:ok? true
   :queued? true
   :event event-v
   :epoch-id nil
   :dispatch-id nil
   :note "Queued dispatch — :dispatch-id and :epoch-id are structurally nil at enqueue time (handler runs out of band). Use tagged-dispatch-sync! if you need :dispatch-id correlation."})

(defn tagged-dispatch-sync!
  "`dispatch-sync` the event and read back the auto-generated
   `:dispatch-id` from re-frame's trace stream so callers can
   correlate the eventual epoch to this dispatch.

   The :dispatch-id comes from re-frame core (rf-3p7 commit af024c3)
   — generated at every `re-frame.events/handle` entry, emitted on
   the `:event` trace's `:tags`. We capture it from
   `@re-frame.trace/traces` immediately after dispatch-sync returns
   (the trace is finished synchronously inside `handle`'s
   `with-trace`).

   The async note on epoch resolution still applies: trace events
   for this dispatch are emitted synchronously, but 10x's
   `::receive-new-traces` event (which appends to
   `:epochs :matches-by-id`) doesn't fire until re-frame.trace's
   ~50ms cb-delivery debounce flushes. Call `collect-after-dispatch`
   with the returned `:dispatch-id` after a bash-side wait past the
   trace-debounce.

   Handler errors: re-frame's default error handler logs to console
   and re-throws, which would propagate through `cljs-eval` as an
   nREPL `:err` and break the bb shim's edn parsing. We catch and
   return a structured `:reason :handler-threw` instead.

   Returns:
     {:ok? true :event ev :dispatch-id <uuid|nil> :epoch-id nil}
     {:ok? false :reason :handler-threw :error ... :error-data ...}"
  [event-v]
  (reset! current-who :claude)
  (try
    (try
      (rf/dispatch-sync event-v)
      (let [dispatch-id (recent-dispatch-id)]
        (when dispatch-id
          (record-claude-dispatch-id! dispatch-id))
        {:ok?         true
         :event       event-v
         :dispatch-id dispatch-id
         ;; Resolved by collect-after-dispatch.
         :epoch-id    nil
         :note        (if dispatch-id
                        "10x's epoch lands after the trace-debounce (~50ms); resolve via collect-after-dispatch with :dispatch-id."
                        "re-frame predates rf-3p7 (commit af024c3) — :dispatch-id auto-generation not available; correlation by :dispatch-id won't work.")})
      (catch :default e
        ;; Surface the throw on the console-log buffer too, tagged
        ;; :handler-error, so console-tail picks it up alongside
        ;; the structured response. Stack matters for these.
        (let [stack (try (.-stack e) (catch :default _ ""))]
          (append-console-entry!
           :error
           [(str "[handler-threw] " (or (ex-message e) (str e)))
            (str event-v)]
           stack
           :handler-error))
        ;; Stringify ex-data — it can carry JS object refs (interceptor
        ;; records, ratoms) that don't edn-roundtrip back to the bb shim.
        {:ok?        false
         :reason     :handler-threw
         :event      event-v
         :error      (or (ex-message e) (str e))
         :error-data (when-let [d (ex-data e)] (pr-str d))}))
    (finally
      (reset! current-who :app))))

(defn last-claude-epoch
  "Most recent epoch dispatched by the skill in this session. Resolves
   by walking the native-epoch-buffer first (newest-first) for the
   first epoch whose `:dispatch-id` appears in `claude-dispatch-ids`,
   then falls back to 10x's buffer when re-frame predates rf-ybv or
   the dispatch-id has aged out of the native buffer."
  []
  (let [ours        (:ids @claude-dispatch-ids)
        from-native (some->> (native-epochs)
                             reverse
                             (some (fn [raw]
                                     (when (contains? ours (:dispatch-id raw))
                                       raw)))
                             coerce-native-epoch)]
    (or from-native
        (when (or (ten-x-loaded?)
                  (not @native-epoch-cb-installed?))
          (some->> (read-10x-epochs)
                   reverse
                   (some (fn [raw]
                           (let [evt-trace   (find-trace raw :event)
                                 dispatch-id (-> evt-trace :tags :dispatch-id)]
                             (when (contains? ours dispatch-id) raw))))
                   coerce-epoch)))))

(def ^:private trace-debounce-settle-ms
  "How long we wait after dispatch-sync before reading 10x's epoch
   buffer. re-frame.trace debounces callback delivery (~50ms); 10x's
   `::receive-new-traces` then runs an event to populate
   `:epochs :matches-by-id`. Plus one render frame for `:render` traces
   to flush. 80ms is comfortably past both."
  80)

(defn- find-epoch-by-dispatch-id
  "Walk 10x's epoch buffer (newest-first; we expect the match within
   the most-recent few entries after a dispatch-sync) for the epoch
   whose event-trace carries the given dispatch-id. nil if not yet
   landed."
  [dispatch-id matches]
  (some (fn [m]
          (when (= dispatch-id (-> (find-trace m :event) :tags :dispatch-id))
            m))
        (reverse matches)))

(defn chained-dispatch-ids
  "Vec of dispatch-ids whose event-trace transitively descends from
   `parent-id` via `:parent-dispatch-id` — direct children, grandchildren,
   and deeper, fired via `:fx [:dispatch ...]` cascades from within the
   parent's handler (or one of its descendants'). Walks `matches` in
   chronological order so the returned vec preserves dispatch order.

   Mirrors the fixed-point closure in `collect-cascade-from-buffer` (the
   rf-4mr / native-buffer path) so the legacy --trace fallback reports
   the same cascade depth as the modern dispatch-and-settle! path."
  [parent-id matches]
  (let [;; Pre-extract [dispatch-id parent-dispatch-id] pairs in
        ;; chronological order so the closure loop walks a flat seq
        ;; instead of re-finding the :event trace inside each match
        ;; on every iteration.
        pairs (->> matches
                   (keep (fn [m]
                           (let [tags (:tags (find-trace m :event))
                                 id   (:dispatch-id tags)
                                 pid  (:parent-dispatch-id tags)]
                             (when id [id pid]))))
                   vec)
        ids   (loop [acc #{parent-id}]
                (let [grown (into acc
                                  (keep (fn [[id pid]]
                                          (when (and pid (contains? acc pid))
                                            id)))
                                  pairs)]
                  (if (= grown acc) acc (recur grown))))]
    (->> pairs
         (keep (fn [[id _pid]]
                 (when (and (not= id parent-id) (contains? ids id))
                   id)))
         vec)))

(defn collect-after-dispatch
  "Companion to `tagged-dispatch-sync!`: after a bash-side wait past
   the trace-debounce, resolve the epoch by `:dispatch-id` correlation
   (rf-3p7 / af024c3 in re-frame core) and return its coerced form
   plus any chained children fired via `:fx [:dispatch ...]`.

   The bash shim drives the wait — `dispatch-and-collect` (the cljs-only
   Promise variant below) doesn't survive the cljs-eval round-trip back
   to babashka.

   Caller pattern (ops.clj's --trace path):
     1. cljs-eval `(tagged-dispatch-sync! ev)` → grab :dispatch-id
     2. Thread/sleep ~80ms (trace-debounce-settle-ms below)
     3. cljs-eval `(collect-after-dispatch <dispatch-id>)` → epoch

   Returns:
     {:ok? true :dispatch-id <id> :epoch-id <int> :epoch <coerced>
      :chained-dispatch-ids [<id> ...]}
     {:ok? false :reason :no-new-epoch :dispatch-id ... :hint ...}"
  [dispatch-id]
  (if (nil? dispatch-id)
    {:ok? false :reason :no-dispatch-id
     :hint "Pass the :dispatch-id returned by tagged-dispatch-sync!. nil here usually means re-frame predates rf-3p7 (commit af024c3) and didn't auto-generate one."}
    (let [matches (read-10x-epochs)
          ours-m  (find-epoch-by-dispatch-id dispatch-id matches)]
      (if ours-m
        (let [chain-ids (chained-dispatch-ids dispatch-id matches)]
          (cond-> {:ok?         true
                   :dispatch-id dispatch-id
                   :epoch-id    (match-id ours-m)
                   :epoch       (coerce-epoch ours-m)}
            (seq chain-ids) (assoc :chained-dispatch-ids chain-ids)))
        {:ok?         false
         :reason      :no-new-epoch
         :dispatch-id dispatch-id
         :hint        "10x has no match carrying this :dispatch-id. trace-enabled? may be false, the handler may have thrown before tracing finished, or the tab may be throttled."}))))

;; ---------------------------------------------------------------------------
;; CLJS-only API — Promise-returning helpers
;; ---------------------------------------------------------------------------
;;
;; The fns below are callable from CLJS only. Their JS Promise return
;; values can't round-trip through cljs-eval back to babashka, so the
;; bash shim never invokes them — bash flows go through the synchronous
;; tagged-dispatch-sync! + collect-after-dispatch pair (or the
;; dispatch-and-settle! handle/poll bridge for cascaded settles). Keep
;; this section walled off so the public/bash-callable surface stays
;; obvious to skim.

(defn dispatch-and-collect
  "dispatch-sync the event, wait for the trace debounce + a render
   frame so renders land in 10x's match-info, then resolve the epoch
   produced via :dispatch-id correlation.

   CLJS-only — the JS Promise return doesn't survive cljs-eval. Bash
   callers use `tagged-dispatch-sync!` + `collect-after-dispatch`
   instead, or `dispatch-and-settle!` for the cascaded variant.

   Returns a JS Promise. Resolves to
   `{:ok? true :epoch-id ... :epoch ...}` or
   `{:ok? false :reason ... :event ...}`."
  [event-v]
  (js/Promise.
   (fn [resolve _reject]
     (let [{:keys [dispatch-id]} (tagged-dispatch-sync! event-v)
           settle (fn settle []
                    (js/requestAnimationFrame
                     (fn []
                       (let [matches (read-10x-epochs)
                             ours-m  (when dispatch-id
                                       (find-epoch-by-dispatch-id dispatch-id matches))]
                         (if ours-m
                           (resolve (clj->js
                                     {:ok?         true
                                      :dispatch-id dispatch-id
                                      :epoch-id    (match-id ours-m)
                                      :epoch       (coerce-epoch ours-m)}))
                           (resolve (clj->js
                                     {:ok?         false
                                      :reason      :no-new-epoch
                                      :event       event-v
                                      :dispatch-id dispatch-id
                                      :hint        "10x did not append a match for this :dispatch-id within the debounce + 1 frame. Possible causes: trace-enabled? false; handler threw before tracing finished; tab throttled; or re-frame predates rf-3p7 (no :dispatch-id generated)."})))))))]
       (js/setTimeout settle trace-debounce-settle-ms)))))

;; Forward-declared — defined in the dispatch-with bridge below.
;; dispatch-and-settle!'s :stub-fx-ids opt validates each id against
;; the :fx registrar via `validate-fx-ids`, then builds an overrides
;; map via `build-stub-overrides` before forwarding to
;; re-frame.core/dispatch-and-settle.
(declare build-stub-overrides validate-fx-ids)

;; ---------------------------------------------------------------------------
;; dispatch-and-settle! — bridge for the bash shim
;; ---------------------------------------------------------------------------
;;
;; re-frame core's `dispatch-and-settle` (rf-4mr, commit f8f0f59)
;; returns a Promise (CLJS) / clojure.core/promise (CLJ) that resolves
;; once the cascade of `:fx [:dispatch ...]` children has settled (an
;; adaptive quiet-period heuristic over the register-epoch-cb stream).
;;
;; The Promise can't round-trip through cljs-eval back to babashka.
;; `dispatch-and-settle!` here stores the eventual resolution in a
;; session-local atom keyed by an opaque handle; the bash shim polls
;; `await-settle <handle>` to read the settled record once it lands.
;;
;; Reconstituting from native-epoch-buffer rather than the Promise's
;; clj->js'd value: re-frame's resolve! walks the result with
;; `(clj->js v :keyword-fn name)`, which stringifies any keywords
;; inside (including :event, :coeffects, :effects). Reading from our
;; native buffer (which `register-epoch-cb` populates in lockstep —
;; both cbs are called from the same `tracing-cb-debounced` batch)
;; keeps keywords intact through the round-trip.

(defn- dispatch-and-settle-fn
  "JS-interop accessor for `re-frame.core/dispatch-and-settle` (rf-4mr,
   commit f8f0f59). Returns nil when re-frame predates rf-4mr — the JS
   symbol simply won't exist on that build.

   Same goog.global / aget-path strategy as `register-epoch-cb-fn`:
   keeps this file compiling against re-frame 1.4.5 (the runtime-test
   build's pinned dep), which doesn't ship the var."
  []
  (when-let [g (some-> js/goog .-global)]
    (aget-path g ["re_frame" "core" "dispatch_and_settle"])))

(defn collect-cascade-from-buffer
  "Collect the cascade rooted at `root-id` from a vec of raw native
   epochs. Walks `:parent-dispatch-id` chains starting from `root-id`
   and returns the coerced records.

   `(collect-cascade-from-buffer root-id)` reads the live buffer.
   `(collect-cascade-from-buffer root-id epochs)` takes raw epochs
   explicitly — public so tests can exercise the cascade walk against
   a synthetic buffer without standing up register-epoch-cb.

   Returns:
     {:root-epoch         <coerced-or-nil>
      :cascaded-epoch-ids [<id>...]
      :cascaded-epochs    [<coerced>...]}
   The root epoch is excluded from `:cascaded-epochs` /
   `:cascaded-epoch-ids`. Returns nil when `root-id` is nil."
  ([root-id] (collect-cascade-from-buffer root-id (native-epochs)))
  ([root-id epochs]
   (when root-id
     (let [;; Reachability closure — keep adding any epoch's dispatch-id
           ;; whose parent is already in the set, until fixed point.
           ids (loop [acc #{root-id}]
                 (let [grown (into acc
                                   (keep #(let [pid (:parent-dispatch-id %)
                                                id  (:dispatch-id %)]
                                            (when (and pid (contains? acc pid))
                                              id))
                                         epochs))]
                   (if (= grown acc) acc (recur grown))))
           root-raw      (some #(when (= root-id (:dispatch-id %)) %) epochs)
           cascaded-raws (filterv #(and (contains? ids (:dispatch-id %))
                                        (not= root-id (:dispatch-id %)))
                                  epochs)
           ctx           {:traces (native-traces) :all-epochs epochs}]
       {:root-epoch         (some-> root-raw (coerce-native-epoch ctx))
        :cascaded-epoch-ids (mapv :dispatch-id cascaded-raws)
        :cascaded-epochs    (mapv #(coerce-native-epoch % ctx) cascaded-raws)}))))

(defonce ^:private settle-pending
  ;; handle-uuid -> {:settled? bool ... result fields}.
  (atom {}))

(defn dispatch-and-settle!
  "Wrapper around `re-frame.core/dispatch-and-settle` (rf-4mr) for the
   bash shim. Dispatches `event-v` synchronously, awaits the cascade
   of `:fx [:dispatch ...]` children using re-frame core's adaptive
   quiet-period heuristic, and stores the settled record in a
   session-local atom keyed by an opaque handle.

   The bash shim polls `await-settle` to recover the resolved record
   once re-frame's Promise has settled — Promises don't round-trip
   through cljs-eval. See `await-settle` for the polling protocol.

   `current-who` flips :claude for the duration of the synchronous
   `dispatch-sync` that runs inside `dispatch-and-settle` (matches
   tagged-dispatch-sync!'s behavior). The root :dispatch-id is
   captured immediately after dispatch-sync via `recent-dispatch-id`
   and accumulated into `claude-dispatch-ids` so `last-claude-epoch`
   keeps pointing at our most recent dispatch.

   The settled record is reconstituted from the native-epoch-buffer
   rather than the Promise's clj->js'd value (see the long comment
   above the section for why).

   `opts` forwards to re-frame.core/dispatch-and-settle. Defaults:
   :timeout-ms 5000, :settle-window-ms 100, :include-cascaded? true.
   Two extra opts (consumed here, stripped before forwarding):
     :stub-fx-ids — vec of fx-id keywords; record-only stubs swap in
                    via `build-stub-overrides` for the duration of
                    the cascade. Stubbed effect values land in
                    `stub-effect-log`.
     :overrides   — explicit `{fx-id stub-fn}` map (rf-ge8). Wins
                    over `:stub-fx-ids` if both supplied. Use this
                    when you need a real stub fn rather than the
                    record-only behavior.

   Returns synchronously:
     {:ok? true :handle <uuid> :event ev :dispatch-id <id> :pending? true
      :stubbed-fx-ids [...]?}
     {:ok? false :reason :dispatch-and-settle-unavailable :hint ...}
     {:ok? false :reason :handler-threw :error ... :event ev}"
  ([event-v] (dispatch-and-settle! event-v {}))
  ([event-v opts]
   (let [stub-ids   (:stub-fx-ids opts)
         validation (when (seq stub-ids) (validate-fx-ids stub-ids))]
     (cond
       ;; Surface the typoed --stub fx-id as a structured error before
       ;; the dispatch fires — otherwise the override map is dead weight
       ;; and the real fx runs unguarded. `:overrides` (a power-user
       ;; explicit map) is left to the caller to validate.
       (and validation (not (:ok? validation)))
       validation

       (nil? (dispatch-and-settle-fn))
       {:ok?    false
        :reason :dispatch-and-settle-unavailable
        :hint   "re-frame predates rf-4mr (commit f8f0f59) — fall back to tagged-dispatch-sync! + collect-after-dispatch."}

       :else
       (let [d-and-s     (dispatch-and-settle-fn)
             handle      (str (random-uuid))
             overrides   (or (:overrides opts)
                             (when-let [ids (seq stub-ids)]
                               (build-stub-overrides ids)))
             settle-opts (dissoc opts :overrides :stub-fx-ids)
             ;; rf-ge8 reads :re-frame/fx-overrides off event meta inside
             ;; do-fx-after; meta survives (dispatch-sync event) so the
             ;; cascade picks it up.
             event-meta  (cond-> event-v
                           overrides (vary-meta assoc :re-frame/fx-overrides overrides))]
         (reset! current-who :claude)
         (try
           (let [p                (d-and-s event-meta settle-opts)
                 root-dispatch-id (recent-dispatch-id)]
             (when root-dispatch-id
               (record-claude-dispatch-id! root-dispatch-id))
             (swap! settle-pending assoc handle
                    {:settled?    false
                     :started-at  (js/Date.now)
                     :event       event-v
                     :dispatch-id root-dispatch-id})
             (-> p
                 (.then (fn [js-result]
                          (let [raw     (js->clj js-result :keywordize-keys true)
                                ok?     (boolean (:ok? raw))
                                cascade (when (and ok? root-dispatch-id)
                                          (collect-cascade-from-buffer root-dispatch-id))]
                            (swap! settle-pending update handle merge
                                   (cond-> {:settled?           true
                                            :ok?                ok?
                                            :event              event-v
                                            :dispatch-id        root-dispatch-id
                                            :epoch-id           (some-> cascade :root-epoch :id)
                                            :epoch              (some-> cascade :root-epoch)
                                            :cascaded-epoch-ids (or (:cascaded-epoch-ids cascade) [])
                                            :cascaded-epochs    (or (:cascaded-epochs cascade) [])}
                                     (not ok?) (assoc :reason (:reason raw)))))))
                 (.catch (fn [err]
                           (swap! settle-pending update handle merge
                                  {:settled? true
                                   :ok?      false
                                   :reason   :promise-rejected
                                   :error    (str err)
                                   :event    event-v}))))
             (cond-> {:ok?         true
                      :handle      handle
                      :event       event-v
                      :dispatch-id root-dispatch-id
                      :pending?    true}
               overrides (assoc :stubbed-fx-ids (vec (sort (keys overrides))))))
           (catch :default e
             (swap! settle-pending dissoc handle)
             (let [stack (try (.-stack e) (catch :default _ ""))]
               (append-console-entry!
                :error
                [(str "[handler-threw] " (or (ex-message e) (str e)))
                 (str event-v)]
                stack
                :handler-error))
             {:ok?        false
              :reason     :handler-threw
              :event      event-v
              :error      (or (ex-message e) (str e))
              :error-data (when-let [d (ex-data e)] (pr-str d))})
           (finally
             (reset! current-who :app))))))))

(defn await-settle
  "Read the settle-pending atom for `handle`. Used by the bash shim's
   polling loop to recover the result of a `dispatch-and-settle!`.

   Returns:
     - settled, success: {:settled? true :ok? true :event ev
                          :dispatch-id <id> :epoch-id <id>
                          :epoch <coerced> :cascaded-epoch-ids [...]
                          :cascaded-epochs [...]}
     - settled, timeout: {:settled? true :ok? false :reason :timeout
                          :event ev}
     - still pending:    {:settled? false :pending? true :handle h}
     - unknown handle:   {:settled? false :ok? false :reason :unknown-handle :handle h}

   `:ok?` is omitted on the still-pending shape because pending isn't an
   error — bash-side polling reads `:settled? r` to decide whether to
   keep polling. The unknown-handle shape carries `:ok? false` so callers
   that gate on `:ok?` correctly classify it as a failure rather than
   conflating with success-but-no-keys.

   On a settled response, the handle is removed from the atom — pollers
   should not call await-settle on the same handle twice."
  [handle]
  (if-let [entry (get @settle-pending handle)]
    (if (:settled? entry)
      (do (swap! settle-pending dissoc handle)
          entry)
      {:settled? false :pending? true :handle handle})
    {:settled? false :ok? false :reason :unknown-handle :handle handle}))

;; ---------------------------------------------------------------------------
;; dispatch-with bridge — fx-overrides for safe iteration
;; ---------------------------------------------------------------------------
;;
;; re-frame core's `dispatch-with` (rf-ge8, commit 2651a30) tags an
;; event with `:re-frame/fx-overrides` meta; `do-fx-after` reads the
;; meta and binds `*current-overrides*` for that event's fx execution
;; (and its synchronous cascade — `tag-with-fx-overrides` propagates
;; the meta to children queued via `:fx [:dispatch ...]`).
;;
;; Why this matters for the shim: the experiment-loop recipe in
;; SKILL.md leans on `undo-step-back` to rewind app-db between probe
;; dispatches. That works for db state but does nothing for already-
;; fired side effects (HTTP request landed, URL changed, local-storage
;; mutated). With dispatch-with the agent stubs the side-effecting fx
;; for the duration of a single probe — no global state to restore.
;;
;; The bash shim drives this via `--stub :http-xhrio` (or several
;; `--stub` flags). Each named fx-id gets `record-only-stub` slotted
;; in: the captured effect value lands in `stub-effect-log`, the
;; original handler doesn't fire. `stubbed-effects-since` reads the
;; log incrementally; `clear-stubbed-effects!` resets it.
;;
;; Custom (non-record-only) stubs that need real fn bodies must use
;; `dispatch-with!` directly via `eval-cljs.sh` — fns can't round-trip
;; cljs-eval, so the CLI shorthand is record-only by design.

(defn- dispatch-with-fn
  "JS-interop accessor for `re-frame.core/dispatch-with` (rf-ge8,
   commit 2651a30). Returns nil when re-frame predates rf-ge8.
   Same goog.global / aget-path strategy as dispatch-and-settle-fn."
  []
  (when-let [g (some-> js/goog .-global)]
    (aget-path g ["re_frame" "core" "dispatch_with"])))

(defn- dispatch-sync-with-fn
  "JS-interop accessor for `re-frame.core/dispatch-sync-with` (rf-ge8)."
  []
  (when-let [g (some-> js/goog .-global)]
    (aget-path g ["re_frame" "core" "dispatch_sync_with"])))

(defonce ^:private stub-effect-log
  ;; FIFO ring-buffer of stubbed-effect invocations. Each entry is a
  ;; {:fx-id kw :value any :ts ms :who kw} map. Bounded so an
  ;; experiment loop iterating over `dispatch-with --stub` doesn't
  ;; accumulate effect maps (which can carry full request bodies)
  ;; indefinitely. `clear-stubbed-effects!` remains the explicit
  ;; reset between experiments.
  (atom {:entries [] :max-size 200}))

(defn record-only-stub
  "Build a record-only stub for `fx-id`: a 1-arg fn that captures its
   value into `stub-effect-log` and returns nil. The original fx's
   side-effect (HTTP, navigation, etc.) is suppressed.

   Public so callers building a custom dispatch-with override map can
   reuse the same logging strategy for some fx-ids while supplying a
   real stub for others."
  [fx-id]
  (fn [value]
    (swap! stub-effect-log
           (fn [{:keys [entries max-size]}]
             {:entries  (vec (take-last max-size
                                        (conj entries
                                              {:fx-id fx-id
                                               :value value
                                               :ts    (js/Date.now)
                                               :who   @current-who})))
              :max-size max-size}))
    nil))

(defn build-stub-overrides
  "Convert a vec of fx-id keywords into a `{fx-id record-only-stub}`
   map suitable for `dispatch-with`. Public for tests."
  [fx-ids]
  (into {} (for [k fx-ids] [k (record-only-stub k)])))

(defn validate-fx-ids
  "Verify every fx-id keyword in `fx-ids` names a registered :fx
   handler. Returns `{:ok? true}` when every id resolves; otherwise
   a structured error listing the bad ids alongside the full request.

   Driven by `--stub` callers (`dispatch-with-stubs!` /
   `dispatch-sync-with-stubs!` / `dispatch-and-settle!`'s `:stub-fx-ids`
   opt). Without it a typoed fx-id (e.g. `:http-xhr` for `:http-xhrio`)
   would be planted in the override map but never matched by a real
   fx-id during do-fx-after, so the real fx fires unguarded — the
   stubbed-fx-ids vec would still claim the substitution applied. Public
   for tests."
  [fx-ids]
  (let [unstubbable (filterv #{:db} fx-ids)
        unknown     (filterv #(nil? (rf-registrar/get-handler :fx %)) fx-ids)]
    (cond
      (seq unstubbable)
      {:ok?         false
       :reason      :unstubbable-fx
       :unstubbable unstubbable
       :requested   (vec fx-ids)
       :hint        ":db is re-frame's app-db effect. Stubbing it would suppress state updates for the probed dispatch."}

      (seq unknown)
      {:ok?       false
       :reason    :unregistered-fx
       :unknown   unknown
       :requested (vec fx-ids)
       :hint      "Unknown fx-id(s) — pass an id registered with reg-fx. Inspect available ids with `(re-frame-pair.runtime/registrar-list :fx)`."}
      :else
      {:ok? true})))

(defn stubbed-effects-since
  "Slice of `stub-effect-log` with `:ts >= since-ts`. Returns
   `{:ok? true :entries [...] :now <ms>}`. Pass back the `:now` from
   a previous call as the next `since-ts` for incremental tailing.

   Single-arity reads the entire log."
  ([] (stubbed-effects-since 0))
  ([since-ts]
   {:ok?     true
    :entries (vec (filter #(>= (:ts %) since-ts) (:entries @stub-effect-log)))
    :now     (js/Date.now)}))

(defn clear-stubbed-effects!
  "Reset the stub-effect-log entries to empty (preserves :max-size).
   `{:ok? true}`."
  []
  (swap! stub-effect-log assoc :entries [])
  {:ok? true})

(defn dispatch-with!
  "Wrapper around `re-frame.core/dispatch-with` (rf-ge8). Queued
   dispatch with selected fx handlers temporarily substituted for
   the duration of THIS event and any synchronous `:fx [:dispatch ...]`
   cascade.

   `current-who` flips :claude for the synchronous portion; the
   handler runs out of band, so handler-side console output tags
   :app (mirror of `tagged-dispatch!`).

   `overrides` is a `{fx-id stub-fn}` map. Use `build-stub-overrides`
   for record-only stubs.

   :reason :dispatch-with-unavailable when re-frame predates rf-ge8."
  [event-v overrides]
  (if-let [d-with (dispatch-with-fn)]
    (do
      (reset! current-who :claude)
      (try
        (d-with event-v overrides)
        {:ok?            true
         :queued?        true
         :event          event-v
         :stubbed-fx-ids (vec (sort (keys overrides)))}
        (finally (reset! current-who :app))))
    {:ok?    false
     :reason :dispatch-with-unavailable
     :hint   "re-frame predates rf-ge8 (commit 2651a30) — upgrade or use a global stub."}))

(defn dispatch-sync-with!
  "Wrapper around `re-frame.core/dispatch-sync-with` (rf-ge8). Same
   override semantics as `dispatch-with!` but synchronous: captures
   the auto-generated `:dispatch-id` from the trace stream so callers
   can correlate the eventual epoch (matches `tagged-dispatch-sync!`).

   Handler errors are caught and surfaced as
   `{:ok? false :reason :handler-threw ...}` (same shape as
   tagged-dispatch-sync!), and a `:handler-error`-tagged entry is
   appended to `console-log`."
  [event-v overrides]
  (if-let [d-sync-with (dispatch-sync-with-fn)]
    (do
      (reset! current-who :claude)
      (try
        (try
          (d-sync-with event-v overrides)
          (let [dispatch-id (recent-dispatch-id)]
            (when dispatch-id (record-claude-dispatch-id! dispatch-id))
            {:ok?            true
             :event          event-v
             :dispatch-id    dispatch-id
             :epoch-id       nil
             :stubbed-fx-ids (vec (sort (keys overrides)))})
          (catch :default e
            (let [stack (try (.-stack e) (catch :default _ ""))]
              (append-console-entry!
               :error
               [(str "[handler-threw] " (or (ex-message e) (str e)))
                (str event-v)]
               stack
               :handler-error))
            {:ok?        false
             :reason     :handler-threw
             :event      event-v
             :error      (or (ex-message e) (str e))
             :error-data (when-let [d (ex-data e)] (pr-str d))}))
        (finally (reset! current-who :app))))
    {:ok?    false
     :reason :dispatch-sync-with-unavailable
     :hint   "re-frame predates rf-ge8 (commit 2651a30) — upgrade or use a global stub."}))

(defn dispatch-with-stubs!
  "Convenience: `dispatch-with!` with record-only stubs for each fx-id
   in `fx-ids`. The bash shim's `--stub <fx-id>` flag drives this —
   passing keywords across cljs-eval is straightforward where passing
   fns is not.

   `validate-fx-ids` runs first: a typoed fx-id short-circuits with
   `:reason :unregistered-fx` before re-frame is touched, so the bash
   shim surfaces the bad input rather than silently letting the real
   fx fire."
  [event-v fx-ids]
  (let [v (validate-fx-ids fx-ids)]
    (if (:ok? v)
      (dispatch-with! event-v (build-stub-overrides fx-ids))
      v)))

(defn dispatch-sync-with-stubs!
  "`dispatch-sync-with!` counterpart of `dispatch-with-stubs!`. Same
   `validate-fx-ids` short-circuit on unregistered fx-ids."
  [event-v fx-ids]
  (let [v (validate-fx-ids fx-ids)]
    (if (:ok? v)
      (dispatch-sync-with! event-v (build-stub-overrides fx-ids))
      v)))

;; ---------------------------------------------------------------------------
;; re-com awareness — re-exported from re-frame-pair.runtime.re-com
;; ---------------------------------------------------------------------------

(def re-com?               re-com/re-com?)
(def re-com-category       re-com/re-com-category)
(def classify-render-entry re-com/classify-render-entry)

;; ---------------------------------------------------------------------------
;; DOM ↔ source bridge — re-exported from re-frame-pair.runtime.dom
;; ---------------------------------------------------------------------------

(def parse-rc-src              dom/parse-rc-src)
(def re-com-debug-enabled?     dom/re-com-debug-enabled?)
(def install-last-click-capture! dom/install-last-click-capture!)
(def last-clicked-element      dom/last-clicked-element)
(def dom-source-at             dom/dom-source-at)
(def dom-find-by-src           dom/dom-find-by-src)
(def dom-fire-click            dom/dom-fire-click)
(def dom-describe              dom/dom-describe)

;; ---------------------------------------------------------------------------
;; Hot-reload probe support — re-exported from re-frame-pair.runtime.registrar
;; ---------------------------------------------------------------------------

(def registrar-handler-ref  registrar/registrar-handler-ref)

;; ---------------------------------------------------------------------------
;; Watch predicate matching — re-exported from re-frame-pair.runtime.epochs
;; ---------------------------------------------------------------------------

(def epoch-matches? epochs/epoch-matches?)

;; ---------------------------------------------------------------------------
;; Time-travel adapter — re-exported from re-frame-pair.runtime.time-travel
;; ---------------------------------------------------------------------------

(def undo-status        time-travel/undo-status)
(def undo-step-back     time-travel/undo-step-back)
(def undo-step-forward  time-travel/undo-step-forward)
(def undo-to-epoch      time-travel/undo-to-epoch)
(def undo-most-recent   time-travel/undo-most-recent)
(def undo-replay        time-travel/undo-replay)

;; ---------------------------------------------------------------------------
;; Version enforcement — re-exported from re-frame-pair.runtime.versions
;; ---------------------------------------------------------------------------

(def version-floors versions/version-floors)
(def version-below? versions/version-below?)
(def version-report versions/version-report)

;; ---------------------------------------------------------------------------
;; Health check
;; ---------------------------------------------------------------------------

(defn- trace-enabled?-fn
  "JS-interop accessor for the `re-frame.trace/trace-enabled?`
   goog-define. Returns the boolean value (true/false) when the
   symbol is reachable, nil otherwise. Probe-based for the same
   reason as the other re-frame.trace accessors — keeps the file
   resilient if a future re-frame moves trace gating off this var."
  []
  (when-let [g (some-> js/goog .-global)]
    (aget-path g ["re_frame" "trace" "trace_enabled_QMARK_"])))

(defn health
  "One-call summary of the runtime's view of the world. Used by
   `discover-app.sh` to confirm the environment is healthy.

   Side effect: installs the last-clicked capture listener and the
   console capture wrapper if they aren't already installed. Both
   are idempotent."
  []
  (install-last-click-capture!)
  (install-console-capture!)
  (install-native-epoch-cb!)
  (install-native-trace-cb!)
  ;; epoch-count throws when 10x isn't loaded (or when running outside
  ;; the browser, e.g. shadow-cljs's node-test build). Health is meant
  ;; to be a best-effort summary; catch and fall back to nil so the
  ;; rest of the report still surfaces.
  (let [ec (try (epoch-count) (catch :default _ nil))]
    {:ok?                 true
     :session-id          session-id
     :ten-x-loaded?       (ten-x-loaded?)
     :trace-enabled?      (trace-enabled?-fn)
     :re-com-debug?       (re-com-debug-enabled?)
     :last-click-capture? true
     :console-capture?    true
     :native-epoch-cb?    @native-epoch-cb-installed?
     :native-trace-cb?    @native-trace-cb-installed?
     :app-db-initialised? (map? @db/app-db)
     :versions            (version-report)
     :epoch-count         ec
     :claude-epoch-count  (:total-count @claude-dispatch-ids)}))

;; ---------------------------------------------------------------------------
;; Session-bootstrap summary
;; ---------------------------------------------------------------------------

(defn value-shape-tag
  "Compact one-level-deep shape descriptor for app-summary. Returns
   a symbol naming the type without dragging the value itself into
   the response. Public so tests can exercise the dispatch directly."
  [v]
  (cond
    (nil? v)        'nil
    (map? v)        'map
    (vector? v)     'vec
    (set? v)        'set
    (sequential? v) 'seq
    (string? v)     'string
    (boolean? v)    'boolean
    (keyword? v)    'keyword
    (number? v)     'number
    :else           'other))

(defn app-summary
  "One-call session-bootstrap bundle. Returns versions, registrar
   inventory, live subs, app-db top-level keys + one-level shape,
   and the health map — saves 5+ separate ops at session start.

   Returned shape:
     {:ok?          true
      :versions     <version-report>
      :registrar    {:event [...] :sub [...] :fx [...] :cofx [...]}
      :live-subs    [<query-v> ...]
      :app-db-keys  [...]               ;; nil if app-db is not a map
      :app-db-shape {<key> <type-sym>}  ;; nil if app-db is not a map
      :health       <health map>
      :ts           <unix-ms>}"
  []
  (let [db @db/app-db]
    {:ok?          true
     :versions     (version-report)
     :registrar    (:by-kind (registrar-describe))
     :live-subs    (subs-live)
     :app-db-keys  (when (map? db) (vec (keys db)))
     :app-db-shape (when (map? db)
                     (into {} (map (fn [[k v]] [k (value-shape-tag v)])) db))
     :health       (health)
     :ts           (js/Date.now)}))
