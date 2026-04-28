(ns re-frame-pair.runtime.console)

;; A ring buffer of `js/console.{log,warn,error,info,debug}` calls,
;; tagged with a `:who` marker so the agent can ask "what did MY
;; dispatch log, vs the user's app?". Installed by
;; `install-console-capture!` from `health` so it's idempotent and
;; runs once per browser runtime. Defaults to `:app` (i.e. user code);
;; `tagged-dispatch!` / `tagged-dispatch-sync!` (in the dispatch
;; module — still in runtime.cljs today) flip it to `:claude` for the
;; duration of their dispatch.
;;
;; Async note: `tagged-dispatch!`'s handler runs out of band, so
;; console.* calls *inside the handler* still tag `:app` — only
;; synchronous calls during the enqueue itself catch `:claude`. Use
;; `tagged-dispatch-sync!` (which runs the handler synchronously)
;; when you need handler output tagged.

;; Session-local console state. Runtime API consumers should use
;; `console-tail-since` / `tagged-dispatch-{!,sync!}` rather than
;; poking these atoms.

(defonce console-log
  (atom {:entries [] :next-id 0 :max-size 500}))

(defonce current-who (atom :app))

(defn- stringify-arg
  "Stringify a console-call argument for the buffer. Avoids holding
   live JS objects (DOM nodes, ratoms, large data structures) that
   would inflate memory and prevent GC."
  [v]
  (cond
    (nil? v)     "nil"
    (string? v)  v
    (number? v)  (str v)
    (boolean? v) (str v)
    (keyword? v) (str v)
    :else        (try (str v)
                      (catch :default _ "<unstringifiable>"))))

(defn append-console-entry!
  "Append a single console event to the ring buffer. `who` defaults
   to `@current-who`; pass `:handler-error` explicitly for the
   exception-catch path."
  ([level args stack] (append-console-entry! level args stack @current-who))
  ([level args stack who]
   (swap! console-log
          (fn [{:keys [entries next-id max-size]}]
            {:entries  (vec (take-last max-size
                                       (conj entries
                                             {:id    next-id
                                              :ts    (js/Date.now)
                                              :level level
                                              :args  (mapv stringify-arg args)
                                              :who   who
                                              :stack stack})))
             :next-id  (inc next-id)
             :max-size max-size}))))

(defn install-console-capture!
  "Wrap `js/console.{log,warn,error,info,debug}` so each call appends
   to the console-log ring buffer in addition to the original
   behaviour. Idempotent — guarded by a window marker so a re-inject
   doesn't double-wrap.

   Silent no-op when there is no browser-side `js/window`
   (e.g. shadow-cljs's `:node-test` build); the runtime still loads
   so unit tests can exercise unrelated machinery."
  []
  (when (and (exists? js/window)
             (not (aget js/window "__rfp_console_capture__")))
    (aset js/window "__rfp_console_capture__" true)
    (doseq [level [:log :warn :error :info :debug]]
      (let [n    (name level)
            orig (aget js/console n)]
        (aset js/window (str "__rfp_orig_console_" n) orig)
        (aset js/console n
              (fn [& args]
                (let [stack (when (#{:error :warn} level)
                              (try (.-stack (js/Error.))
                                   (catch :default _ "")))]
                  (append-console-entry! level args stack))
                (.apply orig js/console (apply array args))))))))

(defn console-capture-installed?
  "True when this browser runtime has installed the console wrapper."
  []
  (boolean (and (exists? js/window)
                (aget js/window "__rfp_console_capture__"))))

(defn install-rf-error-handler!
  "Register a `re-frame.core/reg-event-error-handler` that funnels
   uncaught event-handler exceptions into the console-log ring buffer
   tagged `:who :handler-error`. Same shape as the catch-path entry
   `tagged-dispatch-sync!` already produces, so the *Console / errors*
   recipe stays uniform between rfp-driven dispatches and browser-side
   click dispatches.

   Why a custom error handler rather than relying on re-frame's
   default: re-frame.loggers caches level→fn closures over the
   original `js/console.*` references at module-load time, BEFORE rfp
   is injected. Wrapping `js/console` post-load doesn't redirect those
   cached refs, so re-frame's default error logger writes to
   DevTools but never reaches the rfp ring buffer. Replacing the
   handler is the route that doesn't depend on internal
   re-frame.loggers shape and doesn't race the install order.

   Forwards to the original (pre-wrap) `js/console.error` after
   capturing — DevTools still surfaces the throw and the stack —
   bypassing the rfp wrapper to avoid double-capture into
   `console-log`.

   Probed via `goog.global` so this no-ops cleanly on re-frame builds
   that predate `reg-event-error-handler` (the symbol simply won't
   exist there). Idempotent — guarded by a window marker so a
   re-inject doesn't double-register."
  []
  (when (and (exists? js/window)
             (not (aget js/window "__rfp_rf_error_handler__")))
    (when-let [g (some-> js/goog .-global)]
      (when-let [reg-eeh (some-> g
                                 (aget "re_frame")
                                 (aget "core")
                                 (aget "reg_event_error_handler"))]
        (aset js/window "__rfp_rf_error_handler__" true)
        (reg-eeh
         (fn rfp-rf-error-handler [error event-v]
           (let [stack (try (.-stack error) (catch :default _ ""))
                 msg   (try (.-message error) (catch :default _ (str error)))]
             (append-console-entry!
              :error
              [(str "[handler-threw] " (or msg (str error)))
               (str event-v)]
              stack
              :handler-error))
           (when-let [orig (aget js/window "__rfp_orig_console_error")]
             (try (.call orig js/console error event-v)
                  (catch :default _ nil)))))))))

(defn rf-error-handler-installed?
  "True when this browser runtime has installed the re-frame
   error-handler bridge."
  []
  (boolean (and (exists? js/window)
                (aget js/window "__rfp_rf_error_handler__"))))

(defn console-tail-since
  "Return console entries with `:id >= since-id`, optionally filtered
   by `:who` (one of `:app` / `:claude` / `:handler-error`, or nil =
   all). Returns `{:ok? true :entries [...] :next-id <int>}`.

   `:next-id` is the id the next captured entry will receive — pass
   it back as `since-id` on the next call to tail incrementally."
  ([since-id]      (console-tail-since since-id nil))
  ([since-id who]
   (let [{:keys [entries next-id max-size]} @console-log
         filtered (cond->> entries
                    (some? since-id) (filter #(>= (:id %) since-id))
                    (some? who)      (filter #(= (:who %) who)))]
     {:ok?      true
      :entries  (vec filtered)
      :next-id  next-id
      :max-size max-size})))
