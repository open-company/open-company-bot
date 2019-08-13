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

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue)) ; in-bound requests / notifications to the bot
(defonce aws-sqs-storage-queue (env :aws-sqs-storage-queue)) ; out-bound to the Storage service

;; ----- AWS S3 -----

(defonce digest-bot-static-images-url (or (env :digest-bot-static-images-url) "https://open-company-assets-non-prod.s3.amazonaws.com"))

;; ----- Filestack -----

(defonce filestack-api-key (env :filestack-api-key))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))

;; ----- Bot -----

;; https://api.slack.com/docs/message-formatting
(defonce usage-bullets (str ">- Provide a daily digest that keeps everyone focused on what matters most\n"
                            ">- Add Carrot posts and comments to Slack\n"
                            ">- Unfurl links to Carrot\n"
                            ">- Make it easy to add new Carrot posts from Slack\n"
                            ">- Notify people in Slack for Carrot mentions, comments, and invites\n" 
                            ">- Remind people when it’s time to update their team so no one forgets"))
(defonce usage-message (str "I'm the Carrot Bot, and it seems you have something to say to me. Well... I'm just a Carrot, I've got no ears!\n\n"
                            "Ha! 😜 I kid of course! But for the most part, I do like to stay deep in the soil, out of your way.\n\n"
                            "*Here's what I do:*\n"
                            usage-bullets))
(defonce welcome-message (str "Hey there! Your Slack account has been successfully connected to Carrot. The Carrot bot works in the background to keep Carrot and Slack in sync.\n\n"
                              "*Here's what it does:*\n\n"
                              usage-bullets))