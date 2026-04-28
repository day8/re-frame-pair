(ns app.wrap-handler-smoke
  "Operator-runnable smoke for the runtime-API path of the
   'Trace a handler / sub / fx form-by-form' recipe in SKILL.md.

   Verifies the wrap → dispatch → unwrap cycle round-trips against the
   live `day8.re-frame.tracing.runtime` API in this fixture. Returns a
   map shaped for `eval-cljs.sh` consumption — `{:ok? true ...}` on a
   clean round-trip, or `{:ok? false :reason ...}` on the first
   observable break.

   How to invoke from a re-frame-pair shell with the fixture running:

       scripts/eval-cljs.sh '(do (require (quote app.wrap-handler-smoke))
                                  (app.wrap-handler-smoke/smoke!))'

   The smoke is operator-pending — it needs a live shadow-cljs watch
   and a connected browser tab to evaluate. The textual contract test
   (form-shape, symbol resolution, deps pin) lives in
   `tests/skill_recipe_smoke.bb` and runs in `npm test`."
  (:require [re-frame.macros               :as rf]
            [re-frame.registrar            :as registrar]
            [day8.re-frame.tracing.runtime :as tracing-rt
             :refer-macros [wrap-handler!]]))

(def ^:private smoke-event-id ::probe)

(defn- registered? [kind id]
  (some? (registrar/get-handler kind id)))

(defn smoke!
  "Exercise wrap-handler! → dispatch-sync → unwrap-handler! against a
   synthetic handler. Returns `{:ok? true ...}` on a clean round-trip,
   or `{:ok? false :reason :step ...}` at the first observable break."
  []
  (try
    ;; Cleanup from any previous run that might've left a wrap behind.
    (tracing-rt/unwrap-all!)
    (rf/reg-event-db smoke-event-id (fn [db [_ x]] (assoc db ::probed x)))

    ;; The wrap-handler! macro requires a literal `(fn [...] ...)` —
    ;; fn-traced operates on the AST and the body below is what
    ;; actually gets walked, not the original handler.
    (let [wrapped (wrap-handler! :event smoke-event-id
                                 (fn [db [_ x]] (assoc db ::probed x)))]
      (cond
        (not= [:event smoke-event-id] wrapped)
        {:ok?    false
         :reason :wrap-handler-bad-return
         :got    wrapped}

        (not (tracing-rt/wrapped? :event smoke-event-id))
        {:ok?    false
         :reason :wrapped?-false-after-wrap}

        (not (registered? :event smoke-event-id))
        {:ok?    false
         :reason :registrar-empty-after-wrap}

        :else
        (do
          (rf/dispatch-sync [smoke-event-id ::sentinel])
          (let [unwrap-result (tracing-rt/unwrap-handler! :event smoke-event-id)]
            (cond
              (not (true? unwrap-result))
              {:ok?    false
               :reason :unwrap-handler-not-true
               :got    unwrap-result}

              (tracing-rt/wrapped? :event smoke-event-id)
              {:ok?    false
               :reason :wrapped?-true-after-unwrap}

              (not (registered? :event smoke-event-id))
              {:ok?    false
               :reason :registrar-empty-after-unwrap}

              :else
              {:ok?          true
               :id           [:event smoke-event-id]
               :runtime-api? (tracing-rt/runtime-api?)
               :hint         "wrap → dispatch-sync → unwrap round-tripped cleanly"})))))
    (catch :default e
      {:ok?        false
       :reason     :smoke-threw
       :ex-message (.-message e)
       :ex-data    (ex-data e)})
    (finally
      ;; Best-effort cleanup so a partial run doesn't leak wraps that
      ;; could confuse subsequent operator dispatches.
      (try (tracing-rt/unwrap-all!) (catch :default _ nil))
      (try (registrar/clear-handlers :event smoke-event-id)
           (catch :default _ nil)))))
