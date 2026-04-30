(ns app.events
  (:require [re-frame.core-instrumented :as rf]
            [day8.re-frame.tracing      :refer-macros [fn-traced]]
            [app.db                     :as db]))

;; -----------------------------------------------------------------------------
;; Bootstrap
;; -----------------------------------------------------------------------------

(rf/reg-event-db
 :initialize
 (fn [_ _]
   db/initial-db))

;; -----------------------------------------------------------------------------
;; Counter — the simplest visible state mutation. Wrapped with
;; fn-traced so each sub-form (the threading pipeline, the two
;; updates) lands as a :code entry on the event's trace; runtime.cljs
;; surfaces those as :debux/code on the epoch. Worked example for the
;; "experiment loop" and "Trace a handler/sub/fx form-by-form" recipes
;; in SKILL.md.
;; -----------------------------------------------------------------------------

(rf/reg-event-db
 :counter/inc
 (fn-traced [db _]
   (-> db
       (update :counter inc)
       (update :events-fired inc))))

;; -----------------------------------------------------------------------------
;; Items — a reg-event-db with a non-trivial path-based update.
;; -----------------------------------------------------------------------------

(rf/reg-event-db
 :item/inc-qty
 (fn [db [_ id]]
   (-> db
       (update :items
               (fn [items]
                 (mapv (fn [item]
                         (if (= id (:id item))
                           (update item :qty inc)
                           item))
                       items)))
       (update :events-fired inc))))

;; -----------------------------------------------------------------------------
;; Coupon — a reg-event-fx with a follow-up :dispatch effect. The
;; cascade target is :counter/inc, an existing event — exercises the
;; :effects/fired flattening + the parent->child :dispatch-id link in
;; coerce-epoch without needing a dedicated cascade-target handler.
;; -----------------------------------------------------------------------------

(rf/reg-event-fx
 :coupon/apply
 (fn [{:keys [db]} [_ code]]
   {:db       (-> db
                  (assoc :coupon {:code code :status (if (seq code) :applied :none)})
                  (update :events-fired inc))
    :fx       [(when (seq code)
                 [:dispatch [:counter/inc]])]}))

;; -----------------------------------------------------------------------------
;; Test effects — custom reg-fx handlers for the dispatch-with --stub recipe.
;; -----------------------------------------------------------------------------

(defonce test-fx-log
  (atom []))

(defn clear-test-fx-log! []
  (reset! test-fx-log [])
  {:ok? true})

(defn test-fx-log-snapshot []
  {:ok? true
   :entries @test-fx-log})

(rf/reg-fx
 :test/log-message
 (fn [payload]
   (swap! test-fx-log conj
          {:payload payload
           :ts      (.now js/Date)})
   (.log js/console (str "test fx fired: " (pr-str payload)))))

(rf/reg-event-fx
 :test/log
 (fn [{:keys [db]} [_ message]]
   {:db               (update db :events-fired inc)
    :test/log-message {:message message
                       :source  :direct}}))

(rf/reg-event-fx
 :test/log-then-dispatch
 (fn [{:keys [db]} [_ message]]
   {:db (update db :events-fired inc)
    :fx [[:test/log-message {:message message
                             :source  :root}]
         [:dispatch [:test/log-child message]]]}))

(rf/reg-event-fx
 :test/log-child
 (fn [{:keys [db]} [_ message]]
   {:db               (update db :events-fired inc)
    :test/log-message {:message message
                       :source  :child}}))

;; -----------------------------------------------------------------------------
;; Deliberately broken handlers — for the "experiment loop" recipe to
;; iterate against. They are real bugs the agent should observe and fix.
;; -----------------------------------------------------------------------------

(rf/reg-event-db
 :broken/throw
 (fn [_db _event]
   ;; Intentional: re-frame catches this and reports via re-frame.loggers.
   (throw (ex-info "broken-handler-on-purpose"
                   {:hint "This handler always throws. The agent should observe the error in 10x and propose a fix."}))))

;; -----------------------------------------------------------------------------
;; Large payloads — wire-safety demo / regression handle for the runtime
;; wire layer (issue #4 / bead rfp-zw3w / shipped in v0.1.0-beta.5).
;;
;; `:reports/load`   handler builds the data internally; exercises the
;;                   :effects/db / :app-db/diff/only-after elision path.
;; `:reports/loaded` handler takes the data via the event vector;
;;                   exercises the trace-tags / event-arg elision path.
;; `:reports/clear`  drops the report so the panel can be re-armed.
;; -----------------------------------------------------------------------------

(rf/reg-event-db
 :reports/load
 (fn [db _]
   (-> db
       (assoc-in [:reports :raw]        (db/mock-rows 5000))
       (assoc-in [:reports :loaded-via] :handler-built)
       (update :events-fired inc))))

(rf/reg-event-db
 :reports/loaded
 (fn [db [_ rows]]
   (-> db
       (assoc-in [:reports :raw]        rows)
       (assoc-in [:reports :loaded-via] :event-arg)
       (update :events-fired inc))))

(rf/reg-event-db
 :reports/clear
 (fn [db _]
   (-> db
       (assoc :reports {})
       (update :events-fired inc))))
