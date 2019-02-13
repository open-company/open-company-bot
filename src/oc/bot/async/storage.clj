(ns oc.bot.async.storage
  "Publish Storage triggers to AWS SQS."
  (:require [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.jwt :as jwt]
            [oc.lib.schema :as lib-schema]
            [oc.lib.text :as str]
            [oc.bot.config :as config]))

(def StorageTrigger 
  "A Storage trigger to create a new Entry."
  {    
    :type (schema/enum "new-entry")
    :response {:channel {:id lib-schema/NonBlankStr
                         :name lib-schema/NonBlankStr}
               :response_url lib-schema/NonBlankStr
               :token lib-schema/NonBlankStr}
    :entry-parts {:board-slug lib-schema/NonBlankStr
                  :headline lib-schema/NonBlankStr
                  (schema/optional-key :quote) {:body lib-schema/NonBlankStr
                                                :message_ts lib-schema/NonBlankStr}
                  (schema/optional-key :signpost) lib-schema/NonBlankStr
                  :body lib-schema/NonBlankStr}
    :team-id lib-schema/UniqueID
    :author lib-schema/Author})

(schema/defn ^:always-validate ->trigger :- StorageTrigger
  "
  Given a dialog submission from Slack, a team-id and the Carrot user making the request,
  create the Storage trigger.

  Dialog submission payload example:

  {:action_ts '1550069782.471208'
   :callback_id 'save_message'
   :channel {:id 'C0L2ZM551' :name 'devops'}
   :response_url 'https://hooks.slack.com/app/T06SBMH60/549887168596/koJ4pZaE58Is8jn7cqxkMANr'
   :state 'finished _master_'
   :submission {:board-slug 'general', :body 'Bar', :headline 'Foo', :signpost 'Why it matters'}
   :team {:domain 'opencompanyhq', :id 'T06SBMH60'}
   :token 'aLbD1VFXN31DEgpFIvxu32JV'
   :type 'dialog_submission'
   :user {:id 'U06SBTXJR' :name 'sean'}}
  "
  [payload team-id user]
  (let [state (:state payload)
        trigger {:type "new-entry"
                 :response {:channel (:channel payload)
                            :response_url (:response_url payload)
                            :token (:token payload)}
                 :entry-parts (:submission payload)
                 :team-id (first (:teams user)) ; TODO sort out users w/ multiple Slack teams
                 :author (lib-schema/author-for-user (assoc user :name (jwt/name-for user)))}]
    (if (clojure.string/blank? state)
      trigger
      (-> trigger
        (assoc-in [:entry-parts :quote :body] state)
        (assoc-in [:entry-parts :quote :message_ts] (:action_ts payload))))))

(defn send-trigger! [trigger]
  (timbre/info "Sending request to:" config/aws-sqs-bot-queue)
  (timbre/debug "Storage request:" trigger)
  (sqs/send-message
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
    config/aws-sqs-storage-queue
    trigger)
  (timbre/info "Request sent to:" config/aws-sqs-storage-queue))