(ns oc.bot.app
  (:gen-class)
  (:require [manifold.stream :as stream]
            [taoensso.timbre :as timbre]
            [clojure.string :as string]
            [clojure.java.io :as jio]
            [com.stuartsierra.component :as component]
            [oc.bot.components :as components]
            [oc.bot.async.bot :as bot]
            [oc.bot.config :as c]))

;; ----- System Startup -----

(defn echo-config []
  (println (str "\n"
    "Database: " c/db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "AWS SQS bot queue: " c/aws-sqs-bot-queue "\n"
    "AWS SQS storage queue: " c/aws-sqs-storage-queue "\n"
    "Web URL: " c/web-url "\n"
    "Auth service URL: " c/auth-server-url "\n"
    "Storage service URL: " c/storage-server-url "\n"
    "Log level: " c/log-level "\n"
    "FileStack: " (or c/filestack-api-key "false") "\n"
    "Sentry: " (or c/dsn "false") "\n"
    "  env: " c/sentry-config "\n"
    "\n"
    (when c/intro? "Ready to serve...\n"))))

(defn start
  "Start an instance of the Bot service."
  []

  ;; Log errors go to Sentry
  (timbre/merge-config! {:min-level (keyword c/log-level)})

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (jio/resource "oc/assets/ascii_art.txt")) "\n"))
    "OpenCompany Bot Service\n"))
  (echo-config)

  ;; Start the system, which will start long polling SQS
  (component/start (components/bot-system {:sqs-queue c/aws-sqs-bot-queue
                                           :sentry c/sentry-config
                                           :sqs-msg-handler bot/sqs-handler
                                           :sqs-creds {:access-key c/aws-access-key-id
                                                       :secret-key c/aws-secret-access-key}}))

  (deref (stream/take! (stream/stream)))) ; block forever

(defn -main []
  (start))