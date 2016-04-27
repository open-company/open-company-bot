(ns oc.bot.utils)

(defn predicate-map-lookup [pred-map v]
  (reduce (fn [acc pred]
            (if (pred v)
              (conj acc (get pred-map pred))
              acc))
          #{}
          (keys pred-map)))

(defn next-in [xs x]
  (second (drop-while #(not= % x) xs)))