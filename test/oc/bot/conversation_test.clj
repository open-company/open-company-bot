(ns oc.bot.conversation-test
  (:require [oc.bot.conversation :as conv]
            [clojure.test :as test :refer [is deftest testing]]))

(deftest msg->predicate-test
  (testing "only init messages return predicate"
    (is (nil? (conv/msg->predicate {:type "message" :body "bla"} "x")))
    (is (nil? (conv/msg->predicate {:type :oc.bot/initialize :body "bla"} "x")))
    (is (fn? (conv/msg->predicate {:type :oc.bot/initialize :receiver {:id 1}} "x"))))

  (testing "does not match bot messages"
    (let [bot  "BOT_ID"
          pred (conv/msg->predicate {:type :oc.bot/initialize :receiver {:id 1}} bot)]
      (is (not (pred {:type "message" :channel 1 :user bot})))))

  (testing "matches messages of type 'message' with correct channel"
    (let [pred (conv/msg->predicate {:type :oc.bot/initialize :receiver {:id 1}} "x")]
      (is (false? (pred {:body "abc" :type "user_joined" :channel 1})))
      (is (true? (pred {:body "abc" :type "message" :channel 1})))
      (is (false? (pred {:body "abc" :type "message" :channel 2}))))))

(deftest initialize?-test
  (is (true? (conv/initialize? {:type :oc.bot/initialize :receiver {:id 1}})))
  (is (true? (conv/initialize? {:type :oc.bot/initialize :foo :bar :garbage 2 :receiver {:id 2}})))
  (is (false? (conv/initialize? {:type :oc.bot/initialize :foo :bar :garbage 2 :receiver {:channel 2}}))))

(deftest msg-text->transition-test)

(comment
  (test/run-tests)

  )