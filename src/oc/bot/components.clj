(ns oc.bot.components
    (:require [com.stuartsierra.component :as component]
              [taoensso.timbre :as timbre]
              [oc.lib.sentry.core :refer (map->SentryCapturer)]
              [oc.lib.db.pool :as pool]
              [oc.lib.sqs :as sqs]
              [oc.bot.async.bot :as bot]
              [oc.bot.async.slack-action :as slack-action]
              [oc.bot.config :as c]))

(defrecord RethinkPool [size regenerate-interval]
  component/Lifecycle

  (start [component]
    (timbre/info "[rehinkdb-pool] starting...")
    (let [pool (pool/fixed-pool (partial pool/init-conn c/db-options) pool/close-conn
                                {:size size :regenerate-interval regenerate-interval})]
      (timbre/info "[rehinkdb-pool] started")
      (assoc component :pool pool)))

  (stop [{:keys [pool] :as component}]
    (if pool
      (do
        (timbre/info "[rethinkdb-pool] stopping...")
        (pool/shutdown-pool! pool)
        (timbre/info "[rethinkdb-pool] stopped")
        (assoc component :pool nil))
      component)))

(defrecord BotChannelConsumer [db-pool]
  component/Lifecycle
  
  (start [component]
    (timbre/info "[bot] starting...")
    (bot/start (:pool db-pool))
    (timbre/info "[bot] started")
    (assoc component :bot true))
  
  (stop [{:keys [bot] :as component}]
    (if bot
      (do
        (timbre/info "[bot] stopping...")
        (bot/stop)
        (timbre/info "[bot] stopped")
        (assoc component :bot nil))
      component)))

(defrecord SlackAction [db-pool]
  component/Lifecycle
  (start [component]
    (timbre/info "[slack-action] starting...")
    (slack-action/start (:pool db-pool))
    (timbre/info "[slack-action] started")
    (assoc component :slack-action true))
  (stop [{:keys [bot] :as component}]
    (if bot
      (do
        (timbre/info "[slack-action] stopped")
        (slack-action/stop)
        (assoc component :slack-action nil))
      component)))


(defrecord Handler [handler-fn]
  component/Lifecycle

  (start [component]
    (timbre/info "[handler] started")
    (assoc component :handler handler-fn))

  (stop [component]
    (timbre/info "[handler] stopped")
    (assoc component :handler nil)))

(defn bot-system
  "Define our system components."
  [config-options]
  (let [{:keys [sqs-creds sqs-queue sqs-msg-handler sentry]} config-options]
    (component/system-map
      :sentry-capturer (map->SentryCapturer sentry)
      :db-pool (component/using
                (map->RethinkPool {:size c/db-pool-size :regenerate-interval 5})
                [:sentry-capturer])
      :bot (component/using
              (map->BotChannelConsumer {})
              [:db-pool])
      :slack-action (component/using
                    (map->SlackAction {})
                    [:db-pool])
      :handler (component/using
                (map->Handler {:handler-fn sqs-msg-handler})
                [:sentry-capturer])
      :sqs (component/using
            (sqs/sqs-listener sqs-creds sqs-queue sqs-msg-handler)
            [:sentry-capturer]))))