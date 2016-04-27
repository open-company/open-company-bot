(ns oc.bot.utils-test
  (:require
   [oc.bot.utils :as u]
   [clojure.test :as test :refer [deftest is]]))

(deftest next-in-test
  (is (= :c (u/next-in [:a :b :c] :b)))
  (is (= nil (u/next-in [:a :b :c] nil))))

(deftest predicate-map-lookup-test
  ;; simply using sets here as predicates but any function works
  (is (= #{:a} (u/predicate-map-lookup {#{true} :a #{false} :b} true)))
  (is (= #{:a :b} (u/predicate-map-lookup {#{:x :y} :a #{:x} :b} :x))))

(comment
  (test/run-tests)

  )


