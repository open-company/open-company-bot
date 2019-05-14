(ns oc.bot.unit.digest
  (:require [midje.sweet :refer :all]
            [oc.bot.digest :as sut]))

(def build-slack-digest-messages
  #'oc.bot.digest/build-slack-digest-messages)

(def banner-block {:block "banner"})
(def footer-block {:block "footer"})

(defn- mock-post-chunks
  [n]
  (repeat n [{:block "post"}]))

(facts "regarding bot digest generation"
  (fact "zero posts results in nil (no slack messages)"
    (build-slack-digest-messages banner-block (mock-post-chunks 0) footer-block)
    =>
    nil)
  (fact "a single post results in a single message"
    (build-slack-digest-messages banner-block (mock-post-chunks 1) footer-block)
    =>
    [[banner-block {:block "post"} footer-block]])
  (fact "two posts results in 2 messages, 1 coupled with the banner, 1 coupled with the footer"
    (build-slack-digest-messages banner-block (mock-post-chunks 2) footer-block)
    =>
    [[banner-block {:block "post"}] [{:block "post"} footer-block]])
  (fact "N posts, where N>=2, results in N messages"
    (build-slack-digest-messages banner-block (mock-post-chunks 3) footer-block)
    =>
    [[banner-block {:block "post"}] [{:block "post"}] [{:block "post"} footer-block]]
    ;; -------
    (build-slack-digest-messages banner-block (mock-post-chunks 4) footer-block)
    =>
    [[banner-block {:block "post"}] [{:block "post"}] [{:block "post"}] [{:block "post"} footer-block]]
    ;; -------
    (build-slack-digest-messages banner-block (mock-post-chunks 5) footer-block)
    =>
    [[banner-block {:block "post"}]
     [{:block "post"}]
     [{:block "post"}]
     [{:block "post"}]
     [{:block "post"} footer-block]]
    ))
