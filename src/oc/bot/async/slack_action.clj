(ns oc.bot.async.slack-action
  "
  Async Slack action handling.
  "
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [defun.core :refer (defun-)]
            [if-let.core :refer (if-let*)]
            [taoensso.timbre :as timbre]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [oc.lib.auth :as auth]
            [oc.lib.storage :as storage]
            [oc.bot.async.storage :as storage-async]
            [oc.bot.resources.slack-org :as slack-org-res]
            [oc.bot.resources.team :as team-res]
            [oc.bot.resources.user :as user-res]
            [oc.bot.config :as c]))

(def db-pool (atom false)) ; atom holding DB pool so it can be used for each SQS message

;; ----- core.async -----

(defonce slack-chan (async/chan 10000)) ; buffered channel

(defonce slack-go (atom true))

;; ----- Utility functions -----

(defun- new-post-for

  ([payload]
  ;; Get the author by their slack user ID
  (let [team-id (-> payload :team :id)
        slack-id (-> payload :user :id)
        posting-user (user-res/user-for @db-pool team-id slack-id)]
    (new-post-for payload team-id slack-id posting-user)))
  
  ([payload team-id slack-id user :guard nil?]
  (timbre/warn "No Carrot user for Slack user:" slack-id "found for Slack team:" team-id))

  ([payload _team-id _slack-id user]
  (storage-async/send-trigger! (storage-async/->trigger payload user))))
      
;; ----- Slack API calls -----

(defn- new-post-dialog-for [bot-token payload boards]
  (let [trigger (:trigger_id payload)
        body {
          :trigger_id trigger
          :dialog {
            :title "Create Post in Carrot" ; max 24 chars
            :submit_label "Create"
            :callback_id "add_post"
            :state ""
            :elements [
              {
                :type "select"
                :label "Create as draft or post?"
                :name "status"
                :value "post"
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
              }               
              {
                :type "select"
                :label "Choose a section"
                :name "board-slug"
                :value "all-hands"
                :options (map #(clojure.set/rename-keys % {:name :label :slug :value}) boards)
              }
              {
                :type "text"
                :label "Post title"
                :name "headline"
                :placeholder "Add a title for your Carrot post..."
                :optional false
              }
              {
                :type "textarea"
                :label "Post body"
                :name "body"
                :placeholder "What would you like to say?"
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

(defn- post-dialog-a-for [bot-token payload boards]
  (let [trigger (:trigger_id payload)
        message (:message payload)
        body {
          :trigger_id trigger
          :dialog {
            :title "Save message to Carrot" ; max 24 chars
            :submit_label "Save"
            :callback_id "save-message-a"
            :state (:text message)
            :elements [
              {
                :type "select"
                :label "Save as draft or post?"
                :name "status"
                :value "post"
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
              }                
              {
                :type "select"
                :label "Choose a section"
                :name "board-slug"
                :options (map #(clojure.set/rename-keys % {:name :label :slug :value}) boards)
              }
              {
                :type "text"
                :label "Post title"
                :name "headline"
                :placeholder "Add a title for this Carrot post..."
                :optional false
              }
              {
                :type "select"
                :label "Choose a signpost"
                :name "signpost"
                :value "Why it matters"
                :options [{:label "Why it matters" :value "Why it matters"}
                          {:label "The big picture" :value "The big picture"}
                          {:label "Go deeper" :value "Go deeper"}
                          {:label "What's next" :value "What's next"}
                          {:label "The details" :value "The details"}
                          {:label "Between the lines" :value "Between the lines"}
                          ]
              }
              {
                :type "textarea"
                :label "Additional context"
                :name "body"
                :placeholder "Why it matters, -or- The big picture, etc. ..."
                :optional false
              }
            ]
          }
        }
        result (http/post "https://slack.com/api/dialog.open" {
                  :headers {"Content-type" "application/json"
                            "Authorization" (str "Bearer " bot-token)}
                  :body (json/encode body)})]
    (timbre/info "Result with" bot-token ":" result)))

(defn- post-dialog-b-for [bot-token payload boards]
  (let [trigger (:trigger_id payload)
        message (:message payload)
        body {
          :trigger_id trigger
          :dialog {
            :title "Save message to Carrot" ; max 24 chars
            :submit_label "Save"
            :callback_id "save_message_b"
            :state (:text message)
            :elements [
              {
                :type "select"
                :label "Save as draft or post?"
                :name "status"
                :value "post"
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
              }                
              {
                :type "select"
                :label "Choose a section"
                :name "board-slug"
                :options (map #(clojure.set/rename-keys % {:name :label :slug :value}) boards)
              }
              {
                :type "text"
                :label "Post title"
                :name "headline"
                :placeholder "Add a title for this Carrot post..."
                :optional false
              }
              {
                :type "textarea"
                :label "Why it matters"
                :name "body"
                :placeholder "Additional context on why this Slack message matters..."
                :optional false
              }
            ]
          }
        }
        result (http/post "https://slack.com/api/dialog.open" {
                  :headers {"Content-type" "application/json"
                            "Authorization" (str "Bearer " bot-token)}
                  :body (json/encode body)})]
    (timbre/info "Result with" bot-token ":" result)))

;; ----- Event handling -----

(defun- handle-post-callback
  "
  https://api.slack.com/actions

  We have 3s max to respond to the action with a dialog request.

  Initial action invocation event ('save_message') looks like:
  
  { 
    :action_ts '1538878700.800208'
    :callback_id 'add_post'
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

  Other initial action invocation event ('add_post') looks like:
  
  { 
    :action_ts '1538878700.800208'
    :callback_id 'save_message'
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

  Dialog submission event ('post') looks like:

  {
    :action_ts '1539172586.746607'
    :callback_id 'post'
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
  ([payload :guard #(and (= "message_action" (:type %))
                      (or (= "save_message_a" (:callback_id %))
                          (= "save_message_b" (:callback_id %))
                          (= "add_post" (:callback_id %))))]
  (let [type (:callback_id payload)]
    (timbre/debug (str "Slack '" type "' request of:" payload))
    ;; Bot token from Auth DB
    (if-let* [slack-team-id (-> payload :team :id)
              bot-token (slack-org-res/bot-token-for @db-pool slack-team-id)]
      ;; JWT from Auth service
      (if-let* [slack-user-id (-> payload :user :id)
                user-token (auth/user-token {:slack-user-id slack-user-id
                                             :slack-team-id slack-team-id}
                                             c/auth-server-url
                                             c/passphrase
                                             "Bot")]
        ;; Teams for this Slack from Auth DB & board list from Storage service
        (if-let* [teams (team-res/teams-for @db-pool slack-team-id)
                  boards (storage/board-list-for c/storage-server-url teams user-token)]
          (do
            ;; Dialog request to Slack
            (timbre/debug "Boards:" boards)
            (case type 
              "save_message_a" (post-dialog-a-for bot-token payload boards)
              "save_message_b" (post-dialog-b-for bot-token payload boards)
              "add_post" (new-post-dialog-for bot-token payload boards)))
          (timbre/error "No board list for Slack action:" payload))
        (timbre/error "No JWT possible for Slack action:" payload))
      (timbre/error "No bot-token for Slack action:" payload))))

  ;; User submission of the dialog
  ([payload :guard #(= "dialog_submission" (:type %))]
  (timbre/debug "Slack 'post' request of:" payload)
  (new-post-for payload))

  ;; What message is this?
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