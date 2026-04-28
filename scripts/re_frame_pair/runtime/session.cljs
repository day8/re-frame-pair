(ns re-frame-pair.runtime.session
  (:require [re-frame.db :as db]))

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
