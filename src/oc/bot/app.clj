(ns oc.bot.app
  (:gen-class)
  (:require [manifold.stream :as stream]
            [taoensso.timbre :as timbre]
            [raven-clj.core :as sentry]
            [raven-clj.interfaces :as sentry-interfaces]
            [oc.lib.sentry-appender :as sa]
            [com.stuartsierra.component :as component]
            [oc.bot.components :as components]
            [oc.bot.async.bot :as bot]
            [oc.bot.config :as c]))

;; ----- Unhandled Exceptions -----

;; Send unhandled exceptions to log and Sentry
;; See https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (timbre/error ex "Uncaught exception on" (.getName thread) (.getMessage ex))
     (when c/dsn
       (sentry/capture c/dsn (-> {:message (.getMessage ex)}
                                 (assoc-in [:extra :exception-data] (ex-data ex))
                                 (sentry-interfaces/stacktrace ex)))))))

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
    "Log level: " (name c/log-level) "\n"
    "FileStack: " (or c/filestack-api-key "false") "\n"
    "Sentry: " (or c/dsn "false") "\n"
    "  env: " c/sentry-env "\n"
    (when-not (clojure.string/blank? c/sentry-release)
      (str "  release: " c/sentry-release "\n"))
    "\n"
    (when c/intro? "Ready to serve...\n"))))

(defn start
  "Start an instance of the Bot service."
  []

  ;; Log errors go to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level (keyword c/log-level)
       :appenders {:sentry (sa/sentry-appender c/dsn {:environment c/sentry-env
                                                      :release c/sentry-release})}})
    (timbre/merge-config! {:level (keyword c/log-level)}))

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (clojure.java.io/resource "oc/assets/ascii_art.txt")) "\n"))
    "OpenCompany Bot Service\n"))
  (echo-config)

  ;; Start the system, which will start long polling SQS
  (component/start (components/bot-system {:sqs-queue c/aws-sqs-bot-queue
                                           :sqs-msg-handler bot/sqs-handler
                                           :sqs-creds {:access-key c/aws-access-key-id
                                                       :secret-key c/aws-secret-access-key}}))

  (deref (stream/take! (stream/stream)))) ; block forever

(defn -main []
  (start))