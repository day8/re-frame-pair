(ns app.db)

(def initial-db
  {:counter 0
   :items   [{:id 1 :name "apple" :qty 3}]
   :coupon  {:code "" :status :none}
   :reports {}
   :events-fired 0})

(defn mock-rows
  "Build a substantial vector of mock report rows — large enough to
   reliably exceed re-frame-pair.runtime.wire's per-branch cap
   (~65 KB) so dispatching :reports/load surfaces `:rfp.wire/elided`
   markers on the resulting epoch's `:effects/db` and on subsequent
   `app-db/snapshot` reads. Used by the Large-payload panel as a
   wire-safety demo / regression handle.

   `n=5000` is the calibrated default; any n above ~2500 reliably
   trips elision under the 256 KB total budget."
  [n]
  (vec (for [i (range n)]
         {:id    i
          :date  (str "2026-04-" (inc (mod i 30)))
          :sku   (str "SKU-" (apply str (repeat 5 (char (+ 65 (mod i 26))))))
          :qty   (mod i 100)
          :price (* (mod i 1000) 7)
          :note  (apply str (repeat 30 (char (+ 65 (mod i 26)))))})))
