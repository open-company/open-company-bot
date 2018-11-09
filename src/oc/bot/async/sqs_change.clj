(ns oc.bot.async.sqs-change
  "Publish change service triggers to AWS SQS."
  (:require [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.db.common :as db-common]
            [oc.bot.config :as config]))


(defn- notification-type? [notification-type] (#{:add :update :delete :nux :read} notification-type))

(defn- resource-type? [resource-type] (#{:org :board :entry} resource-type))


(def ChangeTrigger 
  "All change service triggers have the following properties."
  {
   :notification-type (schema/pred notification-type?)
   :resource-type (schema/pred resource-type?)
   :board { :uuid lib-schema/UniqueID }
   :content {
     (schema/optional-key :new) {:read-at lib-schema/ISO8601
                                 :uuid lib-schema/UniqueID
                                 schema/Keyword schema/Any}
   }
   :user (schema/maybe lib-schema/User) ; occassionaly we have a non-user updating an entry, such as the Ziggeo callback
   :notification-at lib-schema/ISO8601})

(schema/defn ^:always-validate ->change-entry-trigger :- ChangeTrigger
  "Given an entry, create the change trigger."
  [post user]
  {
   :notification-type :read
   :resource-type :entry
   :board {:uuid (:board-uuid post)}
   :content {:new (merge {:read-at (db-common/current-timestamp)} post)}
   :user user
   :notification-at (db-common/current-timestamp)
   })

(defn- send-trigger! [trigger]
  (timbre/info "Change request to queue:" config/aws-sqs-change-queue)
  (sqs/send-message
   {:access-key config/aws-access-key-id
    :secret-key config/aws-secret-access-key}
   config/aws-sqs-change-queue
   trigger)
  (timbre/info "Request sent to:" config/aws-sqs-change-queue))

(schema/defn ^:always-validate send-change-trigger! [trigger :- ChangeTrigger]
  (send-trigger! trigger))