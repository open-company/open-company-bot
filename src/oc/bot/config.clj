(ns oc.bot.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]
            [oc.lib.slack :as lib-slack]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

;; ----- System -----

(defonce prod? (= "production" (env :env)))
(defonce intro? (not prod?))

;; ----- Logging -----

(defonce log-level (or (env :log-level) :info))

;; ----- Sentry -----

(defonce dsn (or (env :sentry-dsn) false))

;; ------ OC Web -----

(defonce web-url (or (env :oc-web-url) "http://localhost:3559"))

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue))

;; ----- Bot -----

;; https://api.slack.com/docs/message-formatting
(defonce usage-message (str lib-slack/marker-char ; let interaction service know this came from us
                            "You talkin' to me? You talkin' to me??\n\n"
                            "Well... you shouldn't be, I'm just a Carrot, I've got no ears!\n\n"
                            "Ha! 😜 I kid of course! But for the most part, I do like to stay deep in the soil, out of your way.\n\n"
                            "*Here's what I do:*\n"
                            ">- I ensure all your team's comments from Carrot make it into Slack\n"
                            ">- I also make sure posts shared from Carrot make it to Slack\n"
                            ">- And, I can send you a <" web-url "/profile|daily or weekly digest> of new posts from your team"))