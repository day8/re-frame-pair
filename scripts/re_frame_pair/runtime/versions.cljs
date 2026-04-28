(ns re-frame-pair.runtime.versions)

;; Spec §3.7 says `discover-app.sh` must refuse to connect when a dep
;; is below its minimum floor. CLJS libs don't have a uniform
;; "version" convention in-browser, so this is a best-effort read:
;; try known var names / JS globals, return :unknown when nothing
;; matches. Floors stay nil (no enforcement) for libs that lack a
;; public runtime-readable version var — only re-com currently
;; exposes one (via `re-com.config/version`, a `goog-define`).
;; Enforcement activates per-lib once a public version surface exists.

(def version-floors
  "Floor versions from spec §3.7. `nil` means 'no enforcement yet' —
   the check is plumbed through but does not reject. re-com and
   re-frame both expose a runtime-readable version
   (`re-com.config/version` and `re-frame.config/version` — both
   goog-defines, set by the host build's `:closure-defines`); 10x and
   shadow-cljs do not. Floors stay nil for those until the libs add
   a public version var."
  {:re-frame       nil     ; readable via re-frame.config/version (2026 release); spec placeholder "1.4"
   :re-frame-10x   nil     ; no release-version var in-browser (only api-version int via public/version); spec placeholder "1.9"
   :re-com         nil     ; readable via re-com.config/version; spec placeholder "2.20"
   :shadow-cljs    nil})   ; not a CLJS lib at runtime; spec placeholder "2.28"

(defn- read-goog-global-path
  "Walk a sequence of keys under `goog.global` and return the leaf value,
   or nil if any segment is missing or throws. Pulled out as its own
   defn- so tests can redef the global probe without touching js/goog."
  [path]
  (try
    (let [g (some-> js/goog .-global)]
      (reduce (fn [acc k] (when acc (aget acc k)))
              g path))
    (catch :default _ nil)))

(defn- read-version-of
  "Best-effort version lookup per lib. Returns a string like '2.20.0',
   or :unknown if we can't find it. re-com (`re-com.config/version`)
   and re-frame (`re-frame.config/version`, 2026 release) both expose
   a `goog-define` populated when the host build sets it via
   `:closure-defines`. 10x exposes only an API version int (via
   `day8.re-frame-10x.public/version`'s {:api <n>}), not a release
   string — left as :unknown until 10x adds one."
  [dep]
  (or (case dep
        :re-com       (let [v (read-goog-global-path ["re_com" "config" "version"])]
                        (when (and (string? v) (seq v)) v))
        :re-frame     (let [v (read-goog-global-path ["re_frame" "config" "version"])]
                        (when (and (string? v) (seq v)) v))
        :re-frame-10x nil      ; no release-version var in-browser
        :shadow-cljs  nil      ; not a CLJS runtime lib
        nil)
      :unknown))

(defn version-below?
  "Compare observed to floor as dotted-number strings. Returns true if
   observed is strictly below floor. Returns false if either is
   :unknown or nil (can't enforce what we can't read).

   Public so tests can exercise it without setting up a live
   re-com.config/version goog-define.

   Implementation: pull digit runs from each side, zero-pad both
   sides to the same length, then compare. CLJS's `compare` on
   vectors compares LENGTHS first (unlike JVM Clojure's
   compare-indexed which compares elements first), so without padding
   `[2 20 0]` and `[2 21]` would order by `(> 3 2)` instead of
   `(< 20 21)`. Padding makes the comparison length-invariant."
  [observed floor]
  (and (string? observed) (string? floor)
       (let [->ints #(mapv (fn [s]
                             (let [n (js/parseInt s 10)]
                               (if (js/Number.isNaN n) 0 n)))
                           (re-seq #"\d+" %))
             obs    (->ints observed)
             flr    (->ints floor)
             width  (max (count obs) (count flr))
             pad    (fn [v] (vec (concat v (repeat (- width (count v)) 0))))]
         (neg? (compare (pad obs) (pad flr))))))

(defn version-report
  "Per-dep version read. Returned shape:
     {:by-dep            {:re-frame {:observed '1.4.0' :floor '1.4' :ok? true :enforced? true} ...}
      :all-ok?           true
      :enforcement-live? false     ;; true iff any dep has BOTH a
                                   ;;   non-nil floor AND a readable
                                   ;;   :observed version — otherwise
                                   ;;   the plumbing is in place but
                                   ;;   enforcement is effectively a
                                   ;;   no-op today
      :note              '...'}

   The code path always executes, but callers should not mistake
   `:all-ok? true` for 'versions have been checked'. When
   `:enforcement-live?` is false, `:all-ok?` is vacuously true."
  []
  (let [report (reduce
                (fn [m [dep floor]]
                  (let [observed (read-version-of dep)
                        bad?     (version-below? observed floor)]
                    (assoc m dep {:observed observed
                                  :floor    floor
                                  :ok?      (not bad?)
                                  :enforced? (and (string? observed)
                                                  (string? floor))})))
                {}
                version-floors)
        live?  (boolean (some :enforced? (vals report)))]
    {:by-dep            report
     :all-ok?           (every? :ok? (vals report))
     :enforcement-live? live?
     :note              (if live?
                          "Floors set for at least one dep; enforcement active."
                          "Floors are nil across the board — enforcement plumbed but effectively a no-op. Activates per-lib once a public runtime-readable version var exists.")}))
