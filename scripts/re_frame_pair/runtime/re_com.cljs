(ns re-frame-pair.runtime.re-com
  (:require [clojure.string :as str]))

;; re-com awareness — name-based classification used by epoch
;; reconstruction (renders-from-10x-state) and exposed publicly so
;; recipes can answer "which re-com inputs re-rendered this epoch?".
;;
;; This is a pure-name heuristic over the demunged component name
;; surfaced by 10x's :render trace. It does NOT touch the DOM or any
;; re-com runtime state — see runtime/dom.cljs (when extracted) or
;; the DOM ↔ source bridge section in runtime.cljs for the live-DOM
;; side. Keeping the two concerns separate is why re-com? lives here
;; while parse-rc-src lives with the DOM bridge: re-com? classifies
;; CLJS render trace metadata, parse-rc-src classifies an HTML attr.

(defn re-com?
  "True if the component name names a re-com component. Public so
   tests can exercise the heuristic directly."
  [component-name]
  (and (string? component-name)
       (str/starts-with? component-name "re-com.")))

(defn re-com-category
  "Classify a re-com component by ns segment. Rough — enough to let
   recipes answer 'which inputs re-rendered'. Public for tests.

   Categories follow the re-com source layout (current as of 2026-04):
   layout boxes, inputs (buttons, dropdowns, selection lists, etc.),
   tables, and content (text/typography/throbbers/popovers/etc.)."
  [component-name]
  (cond
    (not (re-com? component-name))                            nil
    (re-find #"re-com\.box"                  component-name)  :layout
    (re-find #"re-com\.gap"                  component-name)  :layout
    (re-find #"re-com\.scroller"             component-name)  :layout
    (re-find #"re-com\.splits"               component-name)  :layout
    (re-find #"re-com\.modal-panel"          component-name)  :layout
    (re-find #"re-com\.buttons"              component-name)  :input
    (re-find #"re-com\.checkbox"             component-name)  :input
    (re-find #"re-com\.radio-button"         component-name)  :input
    (re-find #"re-com\.input-text"           component-name)  :input
    (re-find #"re-com\.input-time"           component-name)  :input
    (re-find #"re-com\.dropdown"             component-name)  :input
    (re-find #"re-com\.single-dropdown"      component-name)  :input
    (re-find #"re-com\.tag-dropdown"         component-name)  :input
    (re-find #"re-com\.selection-list"       component-name)  :input
    (re-find #"re-com\.multi-select"         component-name)  :input
    (re-find #"re-com\.tree-select"          component-name)  :input
    (re-find #"re-com\.typeahead"            component-name)  :input
    (re-find #"re-com\.datepicker"           component-name)  :input
    (re-find #"re-com\.daterange"            component-name)  :input
    (re-find #"re-com\.slider"               component-name)  :input
    (re-find #"re-com\.tabs"                 component-name)  :input
    (re-find #"re-com\.bar-tabs"             component-name)  :input
    (re-find #"re-com\.pill-tabs"            component-name)  :input
    (re-find #"re-com\.horizontal-tabs"      component-name)  :input
    (re-find #"re-com\.simple-v-table"       component-name)  :table
    (re-find #"re-com\.v-table"              component-name)  :table
    (re-find #"re-com\.nested-grid"          component-name)  :table
    (re-find #"re-com\.table-filter"         component-name)  :table
    :else                                                     :content))

(defn classify-render-entry
  "Annotate a render entry with :re-com? and :re-com/category."
  [{:keys [component] :as entry}]
  (let [cat (re-com-category component)]
    (cond-> entry
      (re-com? component) (assoc :re-com? true)
      cat                  (assoc :re-com/category cat))))
