(ns re-frame-pair.runtime.dom
  (:require [clojure.string :as str]
            [re-frame-pair.runtime.epochs :as epochs]))

;; Prerequisites: re-com debug instrumentation enabled, call sites
;; pass `:src (at)`. See docs/initial-spec.md §4.3b.

(defn parse-rc-src
  "Parse re-com's `data-rc-src` attribute into {:file :line}.
   Returns nil on malformed input.

   re-com emits the attribute as a single 'file:line' string from
   `re-com.debug` (see `(str file \":\" line)` at debug.cljs:83). No
   column component, despite Clojure's `(at)` macro carrying one — re-com
   discards it before serialising. Public for tests."
  [attr-val]
  (when (and (string? attr-val) (seq attr-val))
    (let [idx (str/last-index-of attr-val ":")]
      (when (and idx (pos? idx))
        (let [file-part (subs attr-val 0 idx)
              line-part (subs attr-val (inc idx))]
          (when (re-matches #"\d+" line-part)
            {:file file-part
             :line (js/parseInt line-part 10)}))))))

(defn re-com-debug-enabled?
  "Heuristic: re-com debug is enabled if any DOM element carries a
   `data-rc-src` attribute. Public so `discover-app.sh` can surface
   it in the health report.

   This is a DOM observation, not a config read — fine once the app
   has rendered at least one component with `:src (at)`, but may
   misreport on a freshly-loaded page before any render has occurred.

   Returns false in non-browser environments (no `js/document`)."
  []
  (boolean
    (and (exists? js/document)
         (some? (.querySelector js/document "[data-rc-src]")))))

;; Last-clicked capture — passive listener that records the element
;; most recently clicked anywhere on the page. Installed once by
;; `install-last-click-capture!` during injection so ops like
;; `dom/source-at :last-clicked` have something to resolve.

(defonce ^:private last-clicked (atom nil))

(defn install-last-click-capture!
  "Install a single capturing click listener on document that records
   the most recently clicked element. Idempotent — calling twice does
   not double-register (guard via a marker on window).

   Silent no-op when there is no browser-side `js/window` /
   `js/document` (e.g. shadow-cljs's `:node-test` build)."
  []
  (when (and (exists? js/window)
             (exists? js/document)
             (not (aget js/window "__rfp_click_capture__")))
    (aset js/window "__rfp_click_capture__" true)
    (.addEventListener
     js/document
     "click"
     (fn [e] (reset! last-clicked (.-target e)))
     #js {:capture true :passive true})))

(defn last-clicked-element
  "Return the DOM element most recently clicked, or nil if nothing has
   been clicked yet this session. Driven by `install-last-click-capture!`."
  []
  @last-clicked)

(defn- selector-or-last-clicked [selector]
  "If selector is `:last-clicked` (or the string equivalent), return
   the last-clicked element. Otherwise resolve via querySelector."
  (cond
    (or (= selector :last-clicked) (= selector "last-clicked"))
    (last-clicked-element)

    (string? selector)
    (.querySelector js/document selector)

    :else nil))

(defn dom-source-at
  "Given a CSS selector (or `:last-clicked` / `\"last-clicked\"`),
   return the `:src` {:file :line} attached by re-com's debug path.
   Returns a structured result."
  [selector]
  (if-let [el (selector-or-last-clicked selector)]
    (if-let [src-attr (.getAttribute el "data-rc-src")]
      {:ok? true :src (parse-rc-src src-attr) :selector selector}
      {:ok? true :src nil :selector selector
       :reason (if (re-com-debug-enabled?)
                 :no-src-at-this-element
                 :re-com-debug-disabled)})
    {:ok? false :reason :no-element :selector selector
     :hint (when (or (= selector :last-clicked) (= selector "last-clicked"))
             "Nothing clicked this session; interact with the page first, or pass a CSS selector instead.")}))

(defn- src-pattern-matches?
  "True if the element's `data-rc-src` attribute contains
   `file:line`. Used by `dom-find-by-src` and `dom-fire-click`. We
   pull every `[data-rc-src]` and compare strings rather than
   building a CSS selector with the file embedded — single quotes,
   spaces, brackets etc. in real-world paths break the
   string-interpolated selector and there's no portable escape that
   covers every case (`CSS.escape` exists but isn't always available
   in older webviews and doesn't escape `'` itself for attribute
   selectors)."
  [el pattern]
  (when-let [v (.getAttribute el "data-rc-src")]
    (str/includes? v pattern)))

(defn dom-find-by-src
  "Find live DOM elements whose `data-rc-src` matches file+line.
   Returns a list of {:selector :src :tag} summaries."
  [file line]
  (let [pattern (str file ":" line)
        nodes   (.querySelectorAll js/document "[data-rc-src]")]
    (->> (array-seq nodes)
         (filter #(src-pattern-matches? % pattern))
         (mapv (fn [node]
                 {:tag   (.toLowerCase (.-tagName node))
                  :id    (not-empty (.-id node))
                  :class (not-empty (.-className node))
                  :src   (parse-rc-src (.getAttribute node "data-rc-src"))})))))

(defn dom-fire-click
  "Synthesise a click on the element matching file+line. Picks the
   first match if multiple. Returns the epoch produced (if any)."
  [file line]
  (let [pattern (str file ":" line)
        nodes   (array-seq (.querySelectorAll js/document "[data-rc-src]"))
        el      (first (filter #(src-pattern-matches? % pattern) nodes))]
    (if el
      (let [before (epochs/latest-epoch-id)
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
