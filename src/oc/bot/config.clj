(ns oc.bot.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

;; ----- System -----

(defonce processors (.availableProcessors (Runtime/getRuntime)))
(defonce core-async-limit (+ 42 (* 2 processors)))

(defonce prod? (= "production" (env :env)))
(defonce intro? (not prod?))

;; ----- Logging -----

(defonce log-level (or (env :log-level) :info))

;; ----- RethinkDB -----

(defonce db-host (or (env :db-host) "localhost"))
(defonce db-port (or (env :db-port) 28015))
(defonce db-name (or (env :db-name) "open_company_auth"))
(defonce db-pool-size (or (env :db-pool-size) (- core-async-limit 21))) ; conservative with the core.async limit

(defonce db-map {:host db-host :port db-port :db db-name})
(defonce db-options (flatten (vec db-map))) ; k/v sequence as clj-rethinkdb wants it

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
(defonce usage-bullets (str ">- I ensure all your team's comments from Carrot make it into Slack\n"
                            ">- I also make sure posts shared from Carrot make it to Slack\n"
                            ">- I unfurl links to Carrot that are sent in Slack messages\n" 
                            ">- I let people know when they've been mentioned in Carrot\n" 
                            ">- I let people know when you've been invited to a private board\n" 
                            ">- And, I can send <" web-url "/profile|daily or weekly notifications> of new posts from your team"))
(defonce usage-message (str "I'm the Carrot Bot, and it seems you have something to say to me. Well... I'm just a Carrot, I've got no ears!\n\n"
                            "Ha! 😜 I kid of course! But for the most part, I do like to stay deep in the soil, out of your way.\n\n"
                            "*Here's what I do:*\n"
                            usage-bullets))
(defonce welcome-message (str "Hey there! Your Slack account has been successfully connected to Carrot.\n\n"
                              "I'm the Carrot Bot; I work in the background to help out. Here's what I do:\n\n"
                              usage-bullets))