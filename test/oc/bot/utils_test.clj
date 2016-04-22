(ns oc.bot.utils-test
  (:require
   [oc.bot.utils :as u]
   [clojure.test :refer [deftest is]]))

(deftest next-in-test
  (is (= :c (u/next-in [:a :b :c] :b)))
  (is (= nil (u/next-in [:a :b :c] nil))))


