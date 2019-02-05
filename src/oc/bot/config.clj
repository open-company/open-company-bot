(ns oc.bot.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

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

;; ------ URLs -----

(defonce web-url (or (env :oc-web-url) "http://localhost:3559"))
(defonce auth-server-port (Integer/parseInt (or (env :auth-server-port) "3003")))
(defonce auth-server-url (or (env :auth-server-url) (str "http://localhost:" auth-server-port)))
(defonce storage-server-port (Integer/parseInt (or (env :storage-server-port) "3001")))
(defonce storage-server-url (or (env :storage-server-url) (str "http://localhost:" storage-server-port)))
(defonce change-server-port (Integer/parseInt (or (env :change-server-port) "3006")))
(defonce change-server-url (or (env :change-server-url) (str "http://localhost:" change-server-port)))
;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue)) ; in-bound requests / notifications to the bot
(defonce aws-sqs-storage-queue (env :aws-sqs-storage-queue)) ; out-bound to the Storage service

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))

;; ----- Bot -----

;; https://api.slack.com/docs/message-formatting
(defonce usage-bullets (str ">- I ensure comments from Carrot make it into Slack\n"
                            ">- I also make sure posts shared from Carrot make it into Slack\n"
                            ">- I unfurl links to Carrot that are sent in Slack messages\n" 
                            ">- I let people know when they've been mentioned in Carrot\n" 
                            ">- I notify people when they've been invited to a private board\n" 
                            ">- I remind people when they need to update the team\n" 
                            ">- And, I send a daily digest of new posts from the team"))
(defonce usage-message (str "I'm the Carrot Bot, and it seems you have something to say to me. Well... I'm just a Carrot, I've got no ears!\n\n"
                            "Ha! 😜 I kid of course! But for the most part, I do like to stay deep in the soil, out of your way.\n\n"
                            "*Here's what I do:*\n"
                            usage-bullets))
(defonce welcome-message (str "Hey there! Your Slack account has been successfully connected to Carrot.\n\n"
                              "I'm the Carrot Bot; I work in the background to help out. Here's what I do:\n\n"
                              usage-bullets))

(defonce slack-digest-s3-bucket (env :aws-s3-digest-banner-bucket))