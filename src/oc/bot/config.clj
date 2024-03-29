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

(defonce log-level (if-let [log-level (env :log-level)] (keyword log-level) :info))

;; ----- RethinkDB -----

(defonce db-host (or (env :db-host) "localhost"))
(defonce db-port (or (env :db-port) 28015))
(defonce db-name (or (env :db-name) "open_company_auth"))
(defonce db-pool-size (or (env :db-pool-size) (- core-async-limit 21))) ; conservative with the core.async limit

(defonce db-map {:host db-host :port db-port :db db-name})
(defonce db-options (flatten (vec db-map))) ; k/v sequence as clj-rethinkdb wants it

;; ----- Sentry -----

(defonce dsn (or (env :sentry-dsn) false))
(defonce sentry-release (or (env :release) ""))
(defonce sentry-deploy (or (env :deploy) ""))
(defonce sentry-debug  (boolean (or (bool (env :sentry-debug)) (#{:debug :trace} log-level))))
(defonce sentry-env (or (env :environment) "local"))
(defonce sentry-config {:dsn dsn
                        :debug sentry-debug
                        :deploy sentry-deploy
                        :release sentry-release
                        :environment sentry-env})

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

;; How many hours we should wait before resending the welcome message
;; when the user opens the Messages or Home tabs in Slack?
(defonce slack-usage-avoid-repetition-hours (* 24 100)) ;; every 100 days
(defonce slack-app-id (or (env :open-company-slack-app-id) "A5SGHA79P"))

;; https://api.slack.com/docs/message-formatting
(defonce usage-bullets (str ">- Provide a daily digest that keeps everyone focused on what matters most\n"
                            ">- Share Carrot posts to Slack\n"
                            ">- Unfurl links to Carrot"))
(defonce usage-message (str "*Here's what I do:*\n"
                            usage-bullets))
(defonce welcome-message (str "Hey there! Your Slack account has been successfully connected to Carrot. The Carrot bot works in the background to keep Carrot and Slack in sync.\n\n"
                              "*Here's what it does:*\n\n"
                              usage-bullets))