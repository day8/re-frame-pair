(ns app.db)

(def initial-db
  {:counter 0
   :items   [{:id 1 :name "apple"  :qty 3}
             {:id 2 :name "bread"  :qty 1}
             {:id 3 :name "cheese" :qty 2}]
   :coupon  {:code "" :status :none}
   :events-fired 0})
