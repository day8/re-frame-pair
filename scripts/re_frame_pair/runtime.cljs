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
            [re-frame-pair.runtime.dispatch :as dispatch]
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
;; Dispatch — re-exported from re-frame-pair.runtime.dispatch
;; ---------------------------------------------------------------------------

(def claude-dispatch-ids        dispatch/claude-dispatch-ids)
(def tagged-dispatch!           dispatch/tagged-dispatch!)
(def tagged-dispatch-sync!      dispatch/tagged-dispatch-sync!)
(def last-claude-epoch          dispatch/last-claude-epoch)
(def chained-dispatch-ids       dispatch/chained-dispatch-ids)
(def collect-after-dispatch     dispatch/collect-after-dispatch)
(def dispatch-and-collect       dispatch/dispatch-and-collect)
(def dispatch-and-settle-fn     dispatch/dispatch-and-settle-fn)
(def collect-cascade-from-buffer dispatch/collect-cascade-from-buffer)
(def settle-pending             dispatch/settle-pending)
(def dispatch-and-settle!       dispatch/dispatch-and-settle!)
(def await-settle               dispatch/await-settle)
(def dispatch-with-fn           dispatch/dispatch-with-fn)
(def dispatch-sync-with-fn      dispatch/dispatch-sync-with-fn)
(def stub-effect-log            dispatch/stub-effect-log)
(def record-only-stub           dispatch/record-only-stub)
(def build-stub-overrides       dispatch/build-stub-overrides)
(def validate-fx-ids            dispatch/validate-fx-ids)
(def stubbed-effects-since      dispatch/stubbed-effects-since)
(def clear-stubbed-effects!     dispatch/clear-stubbed-effects!)
(def dispatch-with!             dispatch/dispatch-with!)
(def dispatch-sync-with!        dispatch/dispatch-sync-with!)
(def dispatch-with-stubs!       dispatch/dispatch-with-stubs!)
(def dispatch-sync-with-stubs!  dispatch/dispatch-sync-with-stubs!)

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
