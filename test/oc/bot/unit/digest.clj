(ns oc.bot.unit.digest
  (:require [midje.sweet :refer :all]
            [oc.bot.digest :as sut]))

(def get-post-chunks
  #'oc.bot.digest/get-post-chunks)

(def mock-boards [{:slug "general" :name "General"}
                  {:slug "ceo-updates" :name "CEO Updates"}])

(defn mock-post [board]
  {:headline "Test"
   :uuid (str (int (rand 1000)))
   :body "Test body"
   :publisher {:avatar-url ""}
   :must-see (zero? (int (rand 2)))
   :published-at (int (rand 1000))
   :board-slug (:slug board)})

(defn- mock-posts
  "Returns n mocked post chunks, each containing 2 example blocks."
  [n]
  (let [first-board-count (int (rand n))
        second-board-count (- n first-board-count)
        first-board (get mock-boards 0)
        first-board-posts (vec (repeatedly first-board-count #(mock-post first-board)))
        second-board (get mock-boards 1)
        second-board-posts (vec (repeatedly second-board-count #(mock-post second-board)))
        fixed-first-board (assoc first-board :posts first-board-posts)
        fixed-second-board (assoc second-board :posts second-board-posts)]
    {:boards (remove nil? [fixed-first-board fixed-second-board])}))

(facts "regarding bot digest generation"
  (fact "zero posts results in nil (no slack messages)"
    (count (get-post-chunks (mock-posts 0))) => 0)

  (fact "a single post results in a single message"
    (count (get-post-chunks (mock-posts 1))) => 1)

  (fact "two posts results in 2 messages, 1 coupled with the banner, 1 coupled with the footer"
    (count (get-post-chunks (mock-posts 2))) => 2)

  (fact "N posts, where N>=2, results in N messages"
    (count (get-post-chunks (mock-posts 3))) => 3
    ;; -------
    (count (get-post-chunks (mock-posts 4))) => 4
    ;; -------
    (count (get-post-chunks (mock-posts 5))) => 5))