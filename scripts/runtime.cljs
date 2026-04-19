;;;; re-frame-pair.runtime — injected helper namespace
;;;;
;;;; This file is evaluated by `scripts/inject-runtime.sh` on first
;;;; connect. It creates the `re-frame-pair.runtime` namespace inside
;;;; the running browser app and populates it with helpers that the
;;;; skill's ops call through `eval-cljs.sh`.
;;;;
;;;; Design invariants (see docs/initial-spec.md):
;;;;   - No second `register-trace-cb`. All epoch data comes from
;;;;     re-frame-10x's existing trace infrastructure.
;;;;   - The `session-id` sentinel below is re-read on every op. If
;;;;     it's gone, a full page refresh happened and the shim
;;;;     re-injects this file.
;;;;   - 10x internals accessed here are not a public API; several
;;;;     names marked with `TODO verify` need grounding in the spike.
;;;;
;;;; This file is source-of-truth for injection. The shell shim reads
;;;; it and ships the forms over nREPL — so keep it self-contained.

(ns re-frame-pair.runtime
  (:require [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            [re-frame.trace]
            [clojure.data :as data]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Session sentinel
;; ---------------------------------------------------------------------------
;;
;; A random UUID set once per injection. Every subsequent op reads it
;; through `eval-cljs.sh`. If a full page refresh has wiped the
;; browser-side runtime, reading the var throws and the shim knows to
;; re-inject.

(def session-id
  (str (random-uuid)))

(defn sentinel
  "Return the session sentinel. Used by the shim to confirm the runtime
   is still alive in the current browser runtime."
  []
  {:ok?        true
   :session-id session-id
   :installed  (js/Date.now)})

;; ---------------------------------------------------------------------------
;; app-db read/write
;; ---------------------------------------------------------------------------

(defn snapshot
  "Full current app-db."
  []
  @db/app-db)

(defn app-db-at
  "Read a path in app-db."
  [path]
  (get-in @db/app-db path))

(defn app-db-reset!
  "Replace app-db with v. Logged explicitly via `tap>` so the human
   sees what the agent changed."
  [v]
  (tap> {:re-frame-pair/op :app-db/reset
         :previous          @db/app-db
         :next              v
         :t                 (js/Date.now)})
  (reset! db/app-db v)
  {:ok? true})

(defn schema
  "Opt-in app-db schema. Apps that want one can write a spec/malli
   schema to `:re-frame-pair/schema` in app-db (typically via an
   `after` interceptor). Returns nil if the app hasn't opted in."
  []
  (get @db/app-db :re-frame-pair/schema))

;; ---------------------------------------------------------------------------
;; Registrar introspection
;; ---------------------------------------------------------------------------

;; TODO verify against current re-frame: the registrar is accessible
;; as `re-frame.registrar/kind->id->handler`. Confirmed in recent
;; versions; if it moves, update here.

(defn registrar-list
  "Enumerate registered ids under a kind (:event / :sub / :fx / :cofx)."
  [kind]
  (-> (get-in @registrar/kind->id->handler [kind])
      keys
      sort
      vec))

(defn- interceptor-chain-ids
  "Walk an interceptor chain and pull out the ordered :id keys."
  [chain]
  (mapv :id chain))

(defn registrar-describe
  "Return handler metadata for kind+id.
   - For :event — returns kind (reg-event-db vs reg-event-fx, inferred
     from the terminal interceptor's :id) and :interceptor-ids.
   - For :sub / :fx / :cofx — the handler is a plain function; no
     interceptor chain.
   - Source form is not retained by the registrar today, so
     :source is always :not-retained until re-frame A7 lands."
  [kind id]
  (let [entry (get-in @registrar/kind->id->handler [kind id])]
    (cond
      (nil? entry)
      {:ok? false :reason :not-registered :kind kind :id id}

      (= kind :event)
      (let [terminal-id (-> entry last :id)]
        {:ok?             true
         :kind            (case terminal-id
                            :re-frame/db-handler :reg-event-db
                            :re-frame/fx-handler :reg-event-fx
                            :unknown)
         :interceptor-ids (interceptor-chain-ids entry)
         :source          :not-retained})

      :else
      {:ok? true :kind kind :source :not-retained})))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

;; TODO verify: the cache name is `re-frame.subs/query->reaction` in
;; current re-frame. The shape is {query-v reaction}. If renamed, fix
;; here in one place.

(defn subs-live
  "Query vectors currently held in re-frame's subscription cache."
  []
  (->> (some-> subs/query->reaction deref keys)
       (sort-by str)
       vec))

(defn subs-sample
  "Subscribe to query-v and deref once. See docs/initial-spec.md §4.1
   on caching/lifecycle — fine for one-shot probes, not for repeated
   polling outside a reactive context."
  [query-v]
  (try
    @(rf/subscribe query-v)
    (catch :default e
      {:ok? false :reason :sub-error :message (.-message e)})))

;; ---------------------------------------------------------------------------
;; re-frame-10x epoch buffer adapter
;; ---------------------------------------------------------------------------
;;
;; 10x's internals are not a public API. The functions below encapsulate
;; access to the epoch buffer in ONE place so the rest of the runtime —
;; and the skill — doesn't have to know.
;;
;; TODO verify against current 10x source: the exact namespace and
;; shape of the epoch store. Candidates observed in 10x's source tree:
;;
;;   - day8.re-frame-10x.navigation.epochs.events
;;   - day8.re-frame-10x.navigation.epochs.subs
;;   - day8.re-frame-10x.db
;;
;; 10x keeps its own re-frame-like state; the epoch list and current
;; cursor are derivable via 10x's own subscriptions. For v1 we use a
;; conservative read: subscribe to whatever sub 10x exposes for the
;; epoch list, then deref. See the spike (§8a) for the concrete name.

(defn- ten-x-loaded? []
  (boolean
   (try
     (some? (resolve 'day8.re-frame-10x.preload))
     (catch :default _ false))))

(defn- read-10x-epochs
  "Return the vector of epochs currently stored by 10x, or throw with a
   clear error if 10x isn't loaded or its internal shape doesn't match.

   IMPLEMENTATION STUB — this is the single biggest spike deliverable.
   Replace with the confirmed 10x accessor on first validation."
  []
  (when-not (ten-x-loaded?)
    (throw (ex-info "re-frame-10x not loaded" {:reason :ten-x-missing})))
  ;; Placeholder. Real impl reads 10x's epoch buffer — either via a
  ;; 10x subscription (if 10x registers one publicly) or via direct
  ;; ns lookup of an atom.
  (or (some-> (resolve 'day8.re-frame-10x.metamorphic/epochs>)
              deref deref)
      ;; Fallback: empty list rather than error, so higher-level ops
      ;; still compose; they'll report "no epochs available" instead
      ;; of blowing up.
      []))

(defn- coerce-epoch
  "Translate a raw 10x epoch into the shape documented in §4.3a.

   IMPLEMENTATION STUB — depends on 10x's internal record structure.
   For now we pass through known fields and mark unknowns."
  [raw]
  (when raw
    {:id               (:id raw)
     :t                (:t raw)
     :event            (:event raw)
     :coeffects        (:coeffects raw)
     :effects          (:effects raw)
     :effects/fired    (:effects/fired raw)   ;; TODO derive from :effects if 10x doesn't flatten
     :interceptor-chain (:interceptor-chain raw)
     :app-db/diff      (:app-db/diff raw)
     :subs/ran         (:subs/ran raw)
     :subs/cache-hit   (:subs/cache-hit raw)
     :renders          (:renders raw)}))

(defn latest-epoch-id
  "Return the id of 10x's newest epoch, or nil if the buffer is empty."
  []
  (some-> (read-10x-epochs) last :id))

(defn epoch-count
  "Total epochs currently in 10x's ring buffer."
  []
  (count (read-10x-epochs)))

(defn epoch-by-id
  "Return the epoch with matching id, or nil."
  [id]
  (->> (read-10x-epochs)
       (some #(when (= id (:id %)) %))
       coerce-epoch))

(defn last-epoch
  "Most recently appended epoch (any origin)."
  []
  (some-> (read-10x-epochs) last coerce-epoch))

(defn epochs-since
  "Epochs appended after the given id. Empty if id is the current head."
  [id]
  (let [epochs (read-10x-epochs)
        after? (atom (nil? id))]
    (->> epochs
         (filter (fn [e]
                   (if @after?
                     true
                     (do (when (= id (:id e)) (reset! after? true))
                         false))))
         (mapv coerce-epoch))))

(defn epochs-in-last-ms
  "Epochs appended in the last N ms (pull)."
  [ms]
  (let [cutoff (- (js/Date.now) ms)]
    (->> (read-10x-epochs)
         (filter #(>= (or (:t %) 0) cutoff))
         (mapv coerce-epoch))))

;; ---------------------------------------------------------------------------
;; Claude-dispatch tagging
;; ---------------------------------------------------------------------------
;;
;; Event vectors can't carry metadata through re-frame (handlers
;; destructure positionally). Instead we track *the epoch ids our
;; dispatches produced* in a session-local set.

(defonce claude-epoch-ids
  (atom #{}))

(defn- remember-latest-epoch!
  "Record the current head-of-buffer id as a Claude-originated epoch."
  []
  (when-let [id (latest-epoch-id)]
    (swap! claude-epoch-ids conj id)
    id))

(defn tagged-dispatch!
  "Dispatch an event (queued) and record the resulting epoch's id in
   the Claude-originated set. Returns {:ok? true :epoch-id <id>}."
  [event-v]
  (rf/dispatch event-v)
  {:ok? true
   :queued? true
   :event event-v
   ;; Note: id not yet known — dispatch is queued, the epoch appears
   ;; once the handler runs. Callers that want the id should use
   ;; `tagged-dispatch-sync!` instead.
   :epoch-id nil})

(defn tagged-dispatch-sync!
  "`dispatch-sync` the event and record the resulting epoch's id.
   Returns {:ok? true :epoch-id <id>}."
  [event-v]
  (let [before-id (latest-epoch-id)]
    (rf/dispatch-sync event-v)
    (let [after-id (latest-epoch-id)]
      (if (or (nil? before-id) (not= before-id after-id))
        (do (swap! claude-epoch-ids conj after-id)
            {:ok? true :epoch-id after-id :event event-v})
        ;; No new epoch appeared — 10x may not have received the trace
        ;; event yet, or trace-enabled? is false. Flag it.
        {:ok? false :reason :no-new-epoch :event event-v}))))

(defn last-claude-epoch
  "Most recent epoch that the skill dispatched in this session."
  []
  (let [ours @claude-epoch-ids]
    (->> (read-10x-epochs)
         reverse
         (some (fn [raw] (when (contains? ours (:id raw)) raw)))
         coerce-epoch)))

(defn dispatch-and-collect
  "Dispatch synchronously, wait one animation frame so renders land,
   then return the epoch produced. Used by `trace/dispatch-and-collect`.

   Returns a core-async-like result via a JS Promise — the shim awaits."
  [event-v]
  (js/Promise.
   (fn [resolve _reject]
     (let [result (tagged-dispatch-sync! event-v)]
       (if (:ok? result)
         (js/requestAnimationFrame
          (fn []
            ;; One more frame so Reagent's render-triggered traces
            ;; have certainly landed in 10x's epoch.
            (js/requestAnimationFrame
             (fn []
               (resolve (clj->js
                         {:ok?       true
                          :epoch-id  (:epoch-id result)
                          :epoch     (epoch-by-id (:epoch-id result))}))))))
         (resolve (clj->js result)))))))

;; ---------------------------------------------------------------------------
;; re-com awareness
;; ---------------------------------------------------------------------------

(defn- re-com?
  "True if the component name names a re-com component."
  [component-name]
  (and (string? component-name)
       (str/starts-with? component-name "re-com.")))

(defn- re-com-category
  "Classify a re-com component by ns segment. Rough; enough to let
   recipes answer 'which inputs re-rendered'."
  [component-name]
  (cond
    (not (re-com? component-name))       nil
    (re-find #"re-com\.box"   component-name) :layout
    (re-find #"re-com\.input" component-name) :input
    (re-find #"re-com\.selection-list" component-name) :input
    (re-find #"re-com\.dropdown" component-name) :input
    (re-find #"re-com\.button" component-name) :input
    (re-find #"re-com\.text" component-name) :content
    (re-find #"re-com\.typography" component-name) :content
    :else                                 :content))

(defn classify-render-entry
  "Annotate a render entry with :re-com? and :re-com/category."
  [{:keys [component] :as entry}]
  (let [cat (re-com-category component)]
    (cond-> entry
      (re-com? component) (assoc :re-com? true)
      cat                  (assoc :re-com/category cat))))

;; ---------------------------------------------------------------------------
;; DOM ↔ source bridge (re-com `:src`)
;; ---------------------------------------------------------------------------
;;
;; Prerequisites: re-com debug instrumentation enabled, call sites
;; pass `:src (at)`. See docs/initial-spec.md §4.3b.

(defn- parse-rc-src
  "re-com's debug path renders `:src {:file :line :column}` as a
   `data-rc-src` attribute on the DOM. TODO verify the exact
   serialisation format against current re-com."
  [attr-val]
  (when (and attr-val (string? attr-val))
    ;; Assumed format: 'file.cljs:42:8' or 'file.cljs:42'. Tolerate both.
    (let [parts (str/split attr-val #":")]
      (when (>= (count parts) 2)
        {:file   (first parts)
         :line   (js/parseInt (nth parts 1) 10)
         :column (when (>= (count parts) 3)
                   (js/parseInt (nth parts 2) 10))}))))

(defn- re-com-debug-enabled? []
  ;; TODO verify the exact var in re-com.config used to gate debug.
  ;; Placeholder: if the attr exists anywhere in the DOM, we assume
  ;; debug is on.
  (some? (.querySelector js/document "[data-rc-src]")))

(defn dom-source-at
  "Given a CSS selector, return the `:src` {:file :line :column}
   attached by re-com's debug path. Returns a structured result."
  [selector]
  (if-let [el (.querySelector js/document selector)]
    (if-let [src-attr (.getAttribute el "data-rc-src")]
      {:ok? true :src (parse-rc-src src-attr) :selector selector}
      {:ok? true :src nil :selector selector
       :reason (if (re-com-debug-enabled?)
                 :no-src-at-this-element
                 :re-com-debug-disabled)})
    {:ok? false :reason :no-element :selector selector}))

(defn dom-find-by-src
  "Find live DOM elements whose `data-rc-src` matches file+line.
   Returns a list of {:selector :src :tag} summaries."
  [file line]
  (let [pattern (str file ":" line)
        nodes   (.querySelectorAll js/document
                                    (str "[data-rc-src*='" pattern "']"))]
    (->> (array-seq nodes)
         (mapv (fn [node]
                 {:tag   (.toLowerCase (.-tagName node))
                  :id    (not-empty (.-id node))
                  :class (not-empty (.-className node))
                  :src   (parse-rc-src (.getAttribute node "data-rc-src"))})))))

(defn dom-fire-click
  "Synthesise a click on the element matching file+line. Picks the
   first match if multiple. Returns the epoch produced (if any)."
  [file line]
  (let [pattern  (str file ":" line)
        selector (str "[data-rc-src*='" pattern "']")
        el       (.querySelector js/document selector)]
    (if el
      (let [before (latest-epoch-id)
            ev     (js/Event. "click" #js {:bubbles true :cancelable true})]
        (.dispatchEvent el ev)
        {:ok?           true
         :clicked       {:tag (.toLowerCase (.-tagName el))
                         :id  (not-empty (.-id el))}
         :epoch-before  before
         ;; The epoch lands asynchronously; caller should follow up
         ;; with `last-epoch` after a frame if they want it.
         })
      {:ok? false :reason :no-element-at-src :file file :line line})))

(defn dom-describe
  "Summarise a DOM element: its tag, id, classes, `data-rc-src`,
   and the names of event handlers React has attached."
  [selector]
  (if-let [el (.querySelector js/document selector)]
    {:ok?      true
     :tag      (.toLowerCase (.-tagName el))
     :id       (not-empty (.-id el))
     :class    (not-empty (.-className el))
     :src      (parse-rc-src (.getAttribute el "data-rc-src"))
     :text     (let [t (.-textContent el)]
                 (when (and t (< (count t) 200)) t))}
    {:ok? false :reason :no-element :selector selector}))

;; ---------------------------------------------------------------------------
;; Hot-reload probe support
;; ---------------------------------------------------------------------------

(defn registrar-handler-ref
  "Return an opaque identifier for the currently-registered handler
   of kind+id. Used as a pre/post-reload comparison: if the reference
   changes, the reload has taken effect."
  [kind id]
  (let [h (get-in @registrar/kind->id->handler [kind id])]
    (when h
      ;; Function refs aren't reliably `=`, so hash them as strings.
      (hash (str h)))))

;; ---------------------------------------------------------------------------
;; Watch predicate matching
;; ---------------------------------------------------------------------------

(defn epoch-matches?
  "Test a (coerced) epoch against a predicate map built from
   `watch-epochs.sh` CLI args."
  [pred epoch]
  (let [{:keys [event-id event-id-prefix effects timing-ms touches-path sub-ran render]} pred
        ev (:event epoch)]
    (and
     (if event-id        (= event-id (first ev)) true)
     (if event-id-prefix (some-> (first ev) name (str/starts-with? (name event-id-prefix))) true)
     (if effects         (some #(= effects (:fx-id %)) (:effects/fired epoch)) true)
     (if timing-ms       ;; expects [:> n] or [:< n]
                         (let [[op n] timing-ms]
                           (case op
                             :> (> (:time-ms epoch 0) n)
                             :< (< (:time-ms epoch 0) n)
                             true))
                         true)
     (if touches-path    (let [{:keys [only-before only-after]} (:app-db/diff epoch)]
                           (or (some? (get-in only-before touches-path))
                               (some? (get-in only-after touches-path))))
                         true)
     (if sub-ran         (some #(= sub-ran (first (:query-v %))) (:subs/ran epoch)) true)
     (if render          (some #(= render (:component %)) (:renders epoch)) true))))

;; ---------------------------------------------------------------------------
;; Time-travel (planned adapter — not yet implemented)
;; ---------------------------------------------------------------------------
;;
;; 10x has no stable public undo API. The adapter below reaches into
;; 10x's internal epoch navigation. See docs/initial-spec.md §4.6 and
;; Appendix A2 for the hardening path.

(defn- not-yet
  "Uniform stub for time-travel ops until the adapter is wired up."
  [op]
  {:ok? false
   :reason :not-yet-implemented
   :op op
   :see "docs/initial-spec.md §4.6 — awaiting spike deliverable 3"})

(defn undo-step-back    [] (not-yet :undo/step-back))
(defn undo-step-forward [] (not-yet :undo/step-forward))
(defn undo-to-epoch     [_id] (not-yet :undo/to-epoch))
(defn undo-status       [] (not-yet :undo/status))

;; ---------------------------------------------------------------------------
;; Health check
;; ---------------------------------------------------------------------------

(defn health
  "One-call summary of the runtime's view of the world. Used by
   `discover-app.sh` to confirm the environment is healthy."
  []
  {:ok?                 true
   :session-id          session-id
   :ten-x-loaded?       (ten-x-loaded?)
   :trace-enabled?      re-frame.trace/trace-enabled?
   :re-com-debug?       (re-com-debug-enabled?)
   :app-db-initialised? (map? @db/app-db)
   :epoch-count         (epoch-count)
   :claude-epoch-count  (count @claude-epoch-ids)})
