(ns oc.bot.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

(defonce intro? (bool (or (env :intro ) false)))

;; ----- Sentry -----

(defonce dsn (or (env :sentry-dsn) false))

;; ------ OC API -----

(defonce oc-api-endpoint (or (env :oc-api-endpoint) "http://localhost:3000"))

;; ----- AWS SQS / SES -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))
(defonce aws-endpoint (env :aws-endpoint))

(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue))