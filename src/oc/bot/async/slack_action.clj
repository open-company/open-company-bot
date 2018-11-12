(ns oc.bot.async.slack-action
  "
  Async Slack action handling.
  "
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [clojure.walk :refer (keywordize-keys)]
            [defun.core :refer (defun-)]
            [if-let.core :refer (if-let*)]
            [taoensso.timbre :as timbre]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [jsoup.soup :as soup]
            [oc.lib.jwt :as jwt]
            [oc.bot.auth :as auth]
            [oc.bot.storage :as storage]
            [oc.bot.resources.slack-org :as slack-org]
            [oc.bot.resources.team :as team]
            [oc.bot.async.sqs-change :as change]))

(def db-pool (atom false)) ; atom holding DB pool so it can be used for each SQS message

;; ----- core.async -----

(defonce slack-chan (async/chan 10000)) ; buffered channel

(defonce slack-go (atom true))

;; ----- Slack API calls -----

(defn- post-dialog-for [bot-token payload boards]
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
            :callback_id "add_post"
            :state (:text message)
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
                :label "Choose a section"
                :name "section"
                :value "all-hands"
                :options (map #(clojure.set/rename-keys % {:name :label :slug :value}) boards)
              }
              {
                :type "text"
                :label "Post title"
                :name "title"
                :placeholder "Add a title for your Carrot post..."
                :optional false
              }
              {
                :type "textarea"
                :label "Note"
                :name "note"
                :placeholder "Provide context for why this is important..."
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

(defn- post-ephemeral-show-more
  [bot-token payload post]
  (let [response-url (:response_url payload)
        trigger (:trigger_id payload)
        team (:team payload)
        channel (:channel payload)
        ogmessage (:original_message payload)
        user (:user payload)
        content (:body post)
        parsed-body (.text (soup/parse content))]
    (http/post response-url
               {:headers {"Content-type" "application/json"
                          "Authorization" (str "Bearer " bot-token)}
                :body (json/encode {:response_type "ephemeral"
                                    :replace_original false
                                    ;;:thread_ts (str (:ts ogmessage))
                                    :as_user true
                                    :link_names true
                                    :text parsed-body})})))
;; ----- Event handling -----

(defn- show-more
  [db-pool payload]
  (let [callbackid (:callback_id payload)
        post-id (second (clojure.string/split callbackid #":"))
        board-slug (first (clojure.string/split callbackid #":"))]
    ;; Bot token from Auth DB
    (if-let* [slack-team-id (-> payload :team :id)
              bot-token (slack-org/bot-token-for db-pool slack-team-id)]
      ;; JWT from Auth service
      (if-let* [slack-user-id (-> payload :user :id)
                user-token (auth/user-token slack-user-id slack-team-id)]
        ;; Teams for this Slack from Auth DB & post data from the Storage Service
        (if-let* [teams (team/teams-for db-pool slack-team-id)
                  post (storage/post-data-for user-token teams board-slug post-id)]
                 (do
                   (post-ephemeral-show-more bot-token payload post)
                   (change/send-change-trigger!
                    (change/->change-entry-trigger post
                                                   (:claims (jwt/decode user-token)))))
                 (timbre/error "No post for Slack action:" payload))
        (timbre/error "No JWT possible for Slack action:" payload))
      (timbre/error "No bot-token for Slack action:" payload))))

(defun- handle-post-callback
  "
  https://api.slack.com/actions

  We have 3s max to respond to the action with a dialog request.

  Initial action invocation event ('post') looks like:
  
  { 
    :action_ts '1538878700.800208'
    :callback_id 'post'
    :type 'message_action'
    :trigger_id '450676967892.6895731204.3b1d077d82901bb21e3d18e62d20d594'
    :response_url 'https://hooks.slack.com/app/T06SBMH60/452213600886/6BquVZR07zzRqblaB35yYxgC'
    :token 'aLbD1VFXN31DEgpFIvxu32JV'
    :message_ts '1538877805.000100'
    :message {
      :type 'message'
      :user 'U06SBTXJR'
      :text 'test it'
      :client_msg_id 'f027da72-2800-47ac-93b5-b0208652540e'
      :ts '1538877805.000100'
    }
    :user {
      :id 'U06SBTXJR'
      :name 'sean'
    }
    :channel {
      :id 'C10A1P4H2'
      :name 'bot-testing'
    }
    :team {
      :id 'T06SBMH60'
      :domain 'opencompanyhq'
    }
  }

  Dialog submission event ('add_post') looks like:

  {
    :action_ts '1539172586.746607'
    :callback_id 'add_post'
    :type 'dialog_submission'
    :response_url 'https://hooks.slack.com/app/T06SBMH60/453288716437/NrgG1Vo4h3Urcoqy1W13aIdo'
    :token 'aLbD1VFXN31DEgpFIvxu32JV'
    :state '<text of the original message>'
    :submission {
      :status 'draft'
      :section 'all-hands'
      :title 'My Title',
      :note 'My note'
    },
    :user {
      :id 'U06SBTXJR'
      :name 'sean'
    }
    :channel {
      :id 'C0FGNSA2V'
      :name 'development'
    }
    :team {
      :id 'T06SBMH60'
      :domain 'opencompanyhq'
    }
  }
  "
  ;; Initial action callback, respond w/ a dialog request
  ([payload :guard #(= "post" (:callback_id %))]
  (timbre/debug "Slack request of:" payload)
  ;; Bot token from Auth DB
  (if-let* [slack-team-id (-> payload :team :id)
            bot-token (slack-org/bot-token-for @db-pool slack-team-id)]
    ;; JWT from Auth service
    (if-let* [slack-user-id (-> payload :user :id)
              user-token (auth/user-token slack-user-id slack-team-id)]
      ;; Teams for this Slack from Auth DB & board list from Storage service
      (if-let* [teams (team/teams-for @db-pool slack-team-id)
                boards (storage/board-list-for teams user-token)]
        (do
          ;; Dialog request to Slack
          (timbre/debug "Boards:" boards)
          (post-dialog-for bot-token payload boards))
        (timbre/error "No board list for Slack action:" payload))
      (timbre/error "No JWT possible for Slack action:" payload))
    (timbre/error "No bot-token for Slack action:" payload)))

  ;; User submission of the dialog
  ([payload :guard #(= "add_post" (:callback_id %))]
  (timbre/debug "Slack request of:" payload)
  ;; Get the author by their slack user ID
  ;; Initiate a request to the storage queue
  )

  ;; User clicked a button
  ([payload :guard #(= "interactive_message" (:type %))]
     ;; is this show more?
     (when (some #(= "show_more" (:value %)) (:actions payload))
       (show-more @db-pool payload)))

  ([payload]
  (timbre/debug "Slack request of:" payload)
  (timbre/warn "Unknown Slack action callback-id:" (:callback_id payload))))

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
            (handle-post-callback message)
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