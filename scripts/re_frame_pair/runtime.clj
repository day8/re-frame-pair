;;;; re-frame-pair.runtime — compile-time macro half.
;;;;
;;;; Sibling to re_frame_pair/runtime.cljs (the run-time half). Both
;;;; files share the namespace `re-frame-pair.runtime`; CLJS compiles
;;;; .cljs into the user's bundle and uses the .clj here to expand
;;;; macros at compile time. Standard CLJS pattern (cf. re-frame's
;;;; own re_frame/core.cljc + companion .clj sites).
;;;;
;;;; Why these macros exist (rfp-rsg, docs/handler-source-meta.md):
;;;;   re-frame's reg-event-{db,fx} hide the user handler inside an
;;;;   interceptor-wrapper closure (re_frame.std_interceptors), so
;;;;   `handler-source` cannot read the user's call-site through
;;;;   (meta f) — `(meta (:before terminal))` always returns nil.
;;;;   A registration macro that captures (meta &form) at expansion
;;;;   time and stashes it in a side-table is the only reliable fix
;;;;   short of an upstream re-frame change (Appendix A item A7).
;;;;
;;;; Opt-in. Replacing the bare re-frame macros is a user choice:
;;;;
;;;;   (ns app.events
;;;;     (:require [re-frame-pair.runtime :as rfpr
;;;;                :refer-macros [reg-event-db reg-event-fx reg-sub reg-fx]]))
;;;;
;;;;   (rfpr/reg-event-db :counter/inc
;;;;     (fn [db _] (update db :count inc)))
;;;;
;;;; The user's call site's {:file :line :column} flows into
;;;; `re-frame-pair.runtime/-record-source!`. `handler-source` then
;;;; consults that side-table before falling back to (meta f).

(ns re-frame-pair.runtime)

(defn- form-loc
  "Build a {:file :line :column} map from a macro's &form metadata
   plus the *file* dynamic var (the file currently being compiled).
   Values are emitted as a map literal in the macro expansion, so the
   call-site location is baked in at compile time and queryable at
   runtime without re-reading source."
  [form]
  (let [m (meta form)]
    {:file   *file*
     :line   (:line m)
     :column (:column m)}))

(defmacro reg-event-db
  "Drop-in for `re-frame.core/reg-event-db` that also records the
   call-site {:file :line :column} so `re-frame-pair.runtime/handler-source`
   can return it. Same arities as re-frame's: `(reg-event-db id handler)`
   or `(reg-event-db id interceptors handler)`. See ns docstring for why."
  [id & args]
  (let [loc (form-loc &form)]
    `(do
       (re-frame.core/reg-event-db ~id ~@args)
       (re-frame-pair.runtime/-record-source! :event ~id ~loc)
       nil)))

(defmacro reg-event-fx
  "Drop-in for `re-frame.core/reg-event-fx`. See `reg-event-db`."
  [id & args]
  (let [loc (form-loc &form)]
    `(do
       (re-frame.core/reg-event-fx ~id ~@args)
       (re-frame-pair.runtime/-record-source! :event ~id ~loc)
       nil)))

(defmacro reg-sub
  "Drop-in for `re-frame.core/reg-sub`. Variadic to pass through the
   `:<- [...]` input-signal forms and the computation fn. See `reg-event-db`."
  [id & args]
  (let [loc (form-loc &form)]
    `(do
       (re-frame.core/reg-sub ~id ~@args)
       (re-frame-pair.runtime/-record-source! :sub ~id ~loc)
       nil)))

(defmacro reg-fx
  "Drop-in for `re-frame.core/reg-fx`. See `reg-event-db`."
  [id handler]
  (let [loc (form-loc &form)]
    `(do
       (re-frame.core/reg-fx ~id ~handler)
       (re-frame-pair.runtime/-record-source! :fx ~id ~loc)
       nil)))
