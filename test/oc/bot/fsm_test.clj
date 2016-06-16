(ns oc.bot.fsm-test
  (:require [oc.bot.fsm :as fsm]
            [automat.core :as a]
            [clojure.test :as test :refer [is deftest testing]]))

(defn machine [actions-atom]
  (a/compile [(a/or [:a (a/$ :log)]
                    [:b (a/$ :log) (a/or :b1 :b2)]
                    [:c (a/$ :log) (a/+ :c1 :c2 :c3)])]
             {:signal first
              :reducers {:log (fsm/dry-run-wrap (fn [state input] (swap! actions-atom conj input) state))}}))

(deftest possible-transitions-test
  (let [fsm             (machine (atom []))
        with-transition (fn [t] (fsm/possible-transitions fsm (a/advance fsm {} [t])))]
    (is (= #{} (with-transition :a)))
    (is (= #{:b1 :b2} (with-transition :b)))
    (is (= #{:c1} (with-transition :c)))))

(deftest dry-run-wrap-test
  (let [actions    (atom [])
        fsm        (machine actions)]
    (a/advance fsm {::fsm/dry-run false} [:a])
    (is (= [[:a]] @actions))
    (a/advance fsm {::fsm/dry-run true} [:b])
    (is (= [[:a]] @actions))
    (a/advance fsm {::fsm/dry-run false} [:c])
    (is (= [[:a] [:c]] @actions))))

(comment
  (test/run-tests) 

  )