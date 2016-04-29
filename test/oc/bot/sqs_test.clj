(ns oc.bot.sqs-test
  (:require [oc.bot.sqs :as sqs]
            [manifold.deferred :as d]
            [clojure.test :as test :refer [is deftest]]))

(deftest process-test-success
  (let [deleted (atom nil)
        handle (fn [msg] (assoc msg :b 2))
        delete (fn [msg] (reset! deleted msg))
        d      (sqs/process handle delete)]
    (d/chain d (fn [_] (is (= @deleted {:a 1 :b 2}))))
    (d/success! d {:a 1})))

(deftest process-test-failure
  (let [deleted (atom nil)
        handle (fn [msg] (conj msg :b))
        delete (fn [msg] (reset! deleted msg))
        d      (sqs/process handle delete)]
    (d/chain d (fn [_] (is (= @deleted nil))))
    (d/success! d {:a 1})))


(comment
  (test/run-tests)

  )