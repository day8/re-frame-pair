(ns probe-advanced
  "Consumer-side verification for the documented re-frame-10x public
   surface contract — i.e. that `goog.global.day8.re_frame_10x.public`
   resolves under :advanced compilation of a re-frame-pair consumer
   build.

   Compiled as :node-script with :advanced optimizations and run via:
     npx shadow-cljs -A:probe-advanced compile probe-advanced
     node out/probe-advanced.js

   Exits 0 when:
     (a) goog.global.day8.re_frame_10x.public resolves to the public
         ns object — i.e. the upstream `public$` mirror block
         successfully aliased the Closure-mangled `public$` back to
         `public` under :advanced.
     (b) The contract gate (loaded_QMARK_ truthy) holds.
     (c) Representative ^:export vars that re-frame-pair.runtime reads
         (loaded_QMARK_, epochs, latest_epoch_id, epoch_count, etc.)
         all resolve.
     (d) re-frame-pair.runtime is loadable in the same :advanced
         consumer build, AND read-10x-epochs returns successfully —
         the if-let in runtime.cljs walks the same path checked in
         (a), so reaching this point with (a)–(c) green confirms the
         documented un-suffixed path is the one runtime.cljs took.

   Exits 1 with a stderr explanation on the first failure mode.

   Why this exists: the Closure compiler renames `public` (a JS
   reserved word) to `public$` for goog.exportSymbol calls in
   :advanced builds. The 10x-shipped contract (and runtime.cljs's
   aget-path walk) reads the un-suffixed path, so without an alias
   mirror block at the bottom of public.cljs, :advanced consumer
   builds silently fall back to inlined-rf-version walking —
   defeating the premise that consumers don't have to chase version
   slugs. :none builds mask the bug. This probe is the consumer-side
   gate that catches any regression in either upstream's mirror block
   or re-frame-pair's path walk."
  (:require
   [day8.re-frame-10x.public]
   [re-frame-pair.runtime :as rt]))

(defn- fail!
  "Print to stderr and exit non-zero — keeps failure messages visible
   in CI logs even when stdout is captured/buffered."
  [msg]
  (.error js/console msg)
  (.exit js/process 1))

(defn- aget-path
  "Walk a JS object via a vector of property names. Mirrors
   re-frame-pair.runtime/aget-path so the probe verifies the EXACT
   path runtime.cljs walks."
  [obj path]
  (reduce (fn [acc k] (when acc (aget acc k))) obj path))

(defn -main [& _]
  (let [g (some-> js/goog .-global)]
    (when (nil? g)
      (fail! "FAIL: js/goog.global is nil — environment not set up correctly."))

    (let [pub (aget-path g ["day8" "re_frame_10x" "public"])]
      (when (nil? pub)
        (fail! (str "FAIL: goog.global.day8.re_frame_10x.public is absent in"
                    " :advanced. Upstream's `public$` mirror block likely"
                    " isn't running (or re-frame-10x source predates it)."
                    " re-frame-pair would silently fall back to inlined-rf-"
                    "version-path walking — defeating the public-surface"
                    " contract.")))

      (when-not (aget pub "loaded_QMARK_")
        (fail! (str "FAIL: goog.global.day8.re_frame_10x.public.loaded_QMARK_"
                    " is not truthy. The public ns may be partially loaded;"
                    " runtime.cljs/ten-x-public uses this as its contract"
                    " gate and would return nil here.")))

      (let [expected ["loaded_QMARK_" "epochs" "latest_epoch_id"
                      "epoch_count" "epoch_by_id" "all_traces"]
            missing  (filterv #(undefined? (aget pub %)) expected)]
        (when (seq missing)
          (fail! (str "FAIL: " (count missing) " representative ^:export var(s)"
                      " missing from goog.global.day8.re_frame_10x.public after"
                      " :advanced compile: " (pr-str missing) ". These are the"
                      " symbols re-frame-pair.runtime invokes via aget on the"
                      " public ns object — every one must resolve."))))))

  (let [epochs (try (rt/read-10x-epochs)
                    (catch :default e
                      (fail! (str "FAIL: rt/read-10x-epochs threw under :advanced"
                                  " with re-frame-10x.public required: "
                                  (ex-message e)))))]
    (when-not (vector? epochs)
      (fail! (str "FAIL: rt/read-10x-epochs returned a non-vector under :advanced: "
                  (pr-str epochs))))

    (println (str "OK: re-frame-10x public surface reachable in"
                  " re-frame-pair's :advanced consumer build."
                  " goog.global.day8.re_frame_10x.public resolves;"
                  " loaded_QMARK_ + representative ^:export vars present;"
                  " re-frame-pair.runtime/read-10x-epochs returned "
                  (count epochs) " epochs without throwing."))))
