(ns oc.bot.async.slack-action
  "
  Async Slack action handling.
  "
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (if-let*)]
            [taoensso.timbre :as timbre]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [oc.bot.resources.slack-org :as slack-org]))

(def db-pool (atom false)) ; atom holding DB pool so it can be used for each SQS message

;; ----- core.async -----

(defonce slack-chan (async/chan 10000)) ; buffered channel

(defonce slack-go (atom true))

;; ----- Event handling -----

(defn- post-dialog-for [bot-token payload]
  (let [response-url (:response_url payload)
        trigger (:trigger_id payload)
        team (:team payload)
        channel (:channel payload)
        user (:user payload)
        message (:message payload)
        body {
          :trigger_id trigger
          :dialog {
            :title "Save message to Carrot"
            :submit_label "Add"
            :callback_id "foo"
            :state "bar"
            :elements [
              {
                :type "select"
                :label "Save as draft or post?"
                :name "status"
                :value "draft"
                :options [
                  {
                    :label "Draft"
                    :value "draft"
                  }
                  {
                    :label "Post"
                    :value "post"
                  }
                ]
              }                {
                :type "select"
                :label "Choose a section..."
                :name "section"
                :value "all-hands"
                :options [
                  {
                    :label "All-hands"
                    :value "all-hands"
                  }
                  {
                    :label "Decisions"
                    :value "decisions"
                  }
                  {
                    :label "General"
                    :value "general"
                  }
                  {
                    :label "Week in Review"
                    :value "week-in-review"
                  }
                ]
              }
              {
                :type "text"
                :label "Title"
                :name "title"
                :placeholder "A title for your Carrot post..."
                :optional false
              }
              {
                :type "textarea"
                :label "Note"
                :name "note"
                :placeholder "Provide some context for why this is important..."
                :optional true
              }
            ]
          }
        }
        result (http/post "https://slack.com/api/dialog.open" {
                  :headers {"Content-type" "application/json"
                            "Authorization" (str "Bearer " bot-token)}
                  :body (json/encode body)})]
    (timbre/info "Result with" bot-token ":" result)))

(defn- handle-slack-payload
  [payload]
  "
  https://api.slack.com/actions

  We have 3s max to respond to the action with a dialog request.

  Message events look like:
  
  { 
    'message' {
      'type' 'message',
      'user' 'U06SBTXJR',
      'text' 'test it',
      'client_msg_id' 'f027da72-2800-47ac-93b5-b0208652540e',
      'ts' '1538877805.000100'
    },
    'token' 'aLbD1VFXN31DEgpFIvxu32JV',
    'trigger_id' '450676967892.6895731204.3b1d077d82901bb21e3d18e62d20d594',
    'message_ts' '1538877805.000100',
    'user' {
      'idea' 'U06SBTXJR',
      'name' 'sean'
    },
    'action_ts' '1538878700.800208',
    'callback_id' 'post',
    'type' 'message_action',
    'response_url' 'https://hooks.slack.com/app/T06SBMH60/452213600886/6BquVZR07zzRqblaB35yYxgC',
    'channel' {
      'id' 'C10A1P4H2',
      'name' 'bot-testing'
    },
    'team' {
      'id' 'T06SBMH60',
      'domain' 'opencompanyhq'
    }
  }
  "
  (timbre/debug "Slack request of:" payload)
  (if-let* [team-id (-> payload :team :id)
            bot-token (slack-org/bot-token-for @db-pool team-id)]
    (post-dialog-for bot-token payload)
    (timbre/error "No bot-token for:" payload)))
  
;; ----- Event loop -----

(defn- slack-action-loop []
  (reset! slack-go true)
  (timbre/info "Starting Slack action...")
  (async/go (while @slack-go
    (timbre/debug "Slack action waiting...")
    (let [message (<! slack-chan)]
      (timbre/debug "Processing message on Slack action channel...")
      (if (:stop message)
        (do (reset! slack-go false) (timbre/info "Slack action stopped."))
        (async/thread
          (try
            (handle-slack-payload message)
          (catch Exception e
            (timbre/error e)))))))))

;; ----- Event triggering -----

(defn send-payload! [payload]
  (>!! slack-chan payload))

;; ----- Component start/stop -----

(defn start
  "Start the core.async event loop."
  [pool]
  (reset! db-pool pool) ; hold onto the DB pool reference
  (slack-action-loop))

(defn stop
  "Stop the the core.async event loop."
  []
  (when @slack-go
    (timbre/info "Stopping Slack action...")
    (>!! slack-chan {:stop true}))
  (reset! db-pool false))