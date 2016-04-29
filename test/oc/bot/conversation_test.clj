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

(deftest msg-text->transition-test
  (is (= [:yes] (conv/msg-text->transition "y" #{:yes})))
  (is (nil? (conv/msg-text->transition "y" #{})))
  (is (= [:yes] (conv/msg-text->transition "YES" #{:yes})))
  (is (nil? (conv/msg-text->transition "YES" #{})))
  (is (= [:no] (conv/msg-text->transition "no" #{:yes :no})))
  (is (nil? (conv/msg-text->transition "no" #{})))

  ;; Currency
  (is (= [:currency ::conv/eur] (conv/msg-text->transition "eur" #{:currency :no})))
  (is (nil? (conv/msg-text->transition "eur" #{:no})))
  (is (= [:currency ::conv/eur] (conv/msg-text->transition "€" #{:currency :no})))
  (is (nil? (conv/msg-text->transition "€" #{:no})))
  (is (= [:currency ::conv/usd] (conv/msg-text->transition "$" #{:currency :no})))
  (is (nil? (conv/msg-text->transition "$" #{:no})))

  ;; Mixing Yes/No with wildcard string matches
  (is (= [:no] (conv/msg-text->transition "no" #{:no :str})))
  (is (= [:str "hello"] (conv/msg-text->transition "hello" #{:no :str})))
  (is (= [:no] (conv/msg-text->transition "n" #{:no :yes :str})))
  (is (= [:yes] (conv/msg-text->transition "yes" #{:no :yes :str}))))

(deftest message-segment-id-test
  (is (= [:init :yes] (conv/message-segment-id {:updated {} :stage :init} :yes)))
  (is (= [:name :yes-after-update] (conv/message-segment-id {:updated {:name "hello"} :stage :name} :yes))))

(deftest stage-confirmed?-test
  (is (conv/stage-confirmed? {:value {:confirmed #{:name} :stage :name}}))
  (is (not (conv/stage-confirmed? {:value {:confirmed #{:name} :stage :description}}))))

(comment
  (test/run-tests)

  )