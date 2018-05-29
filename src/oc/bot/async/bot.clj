(ns oc.bot.async.bot
  "Consume bot requests from SQS, adjust them for our use, and then do the needed bot operation."
  (:require [clojure.string :as s]
            [clojure.core.async :as async :refer (<!! >!!)]
            [cuerdas.core :as str]
            [manifold.stream :as stream]
            [com.stuartsierra.component :as component]
            [amazonica.aws.sqs :as aws-sqs]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]
            [jsoup.soup :as soup]
            [clj-time.format :as time-format]
            [raven-clj.core :as sentry]
            [raven-clj.interfaces :as sentry-interfaces]
            [oc.lib.sentry-appender :as sa]
            [oc.lib.sqs :as sqs]
            [oc.lib.slack :as slack]
            [oc.bot.digest :as digest]
            [oc.bot.resources.slack-org :as slack-org]
            [oc.bot.config :as c]))

(def db-pool (atom false)) ; atom holding DB pool so it can be used for each SQS message

;; ----- core.async -----

(defonce bot-chan (async/chan 10000)) ; buffered channel to protect Slack from too many requests

(defonce bot-go (atom nil))

;; ----- Utility functions -----

(defn- slack-handler [conn msg-idx msg] (prn msg))

(defn- real-user? [user]
  (and (not (:deleted user))
       (not (:is_bot user))
       (not (:is_restricted user))
       (not= "USLACKBOT" (:id user))))

(defn- first-name [name]
  (first (clojure.string/split name #"\s")))

(defn- clean-text [text]
  (-> text
    (s/replace #"&nbsp;" " ")
    (str/strip-tags)
    (str/strip-newlines)))

(def iso-format (time-format/formatters :date-time))
(def date-format (time-format/formatter "MMMM d"))

(defn- post-date [timestamp]
  (let [d (time-format/parse iso-format timestamp)]
    (time-format/unparse date-format d)))

;; ----- SQS handling -----

(defn- adjust-receiver
  "Inspect the receiver field and return one or more initialization messages
   with proper DM channels."
  [msg]
  (let [token (-> msg :bot :token)
        type  (-> msg :receiver :type)]
    (timbre/info "Adjusting receiver" {:type type})
    (cond
      ;; Directly to a specific user
      (and (= :user type) (s/starts-with? (-> msg :receiver :id) "U"))
      [(assoc msg :receiver {:id (slack/get-dm-channel token (-> msg :receiver :id))
                             :type :channel
                             :dm true})]
      
      ;; To a specific channel
      (and (= :channel type) (not (s/blank? (-> msg :receiver :id))))
      [(assoc msg :receiver {:id (-> msg :receiver :id)
                             :type :channel
                             :dm false})]

      ;; To every full member of the Slack org (fan out)
      (= :all-members type)
      (for [u (filter real-user? (slack/get-users token))]
        (let [with-first-name (assoc-in msg [:script :params :user/name] (first-name (:real_name u)))]
          (assoc with-first-name :receiver {:type :channel
                                            :id (slack/get-dm-channel token (:id u))
                                            :dm true})))

      :else
      (throw (ex-info "Failed to adjust receiver" {:msg msg})))))

(defn- read-message-body
  "
  Try to parse as json, otherwise use read-string.
  "
  [msg]
  (try
    (json/parse-string msg true)
    (catch Exception e
      (read-string msg))))

(defn sqs-handler
  "Handle an incoming SQS message to the bot."
  [msg done-channel]
  (let [msg-body (read-message-body (:body msg))
        error (if (:test-error msg-body) (/ 1 0) false)] ; a message testing Sentry error reporting
    (timbre/infof "Received message from SQS: %s\n" msg-body)
    (>!! bot-chan msg-body)) ; send the message to the bot's channel
  (sqs/ack done-channel msg))

;; ----- Bot Request handling -----

(defn- send-private-board-notification [msg]
  (let [notifications (-> msg :content :notifications)
        board (-> msg :content :new)
        user (:user msg)
        slack-bots (:slack-bots user)]

    (doseq [team (:teams user)]
      (let [slack-bot (first ((keyword team) slack-bots))]
        (doseq [notify notifications]
          (let [slack-info (first (vals (:slack-users notify)))]
            (when slack-info
              (let [token (:token slack-bot)
                    board-url (s/join "/" [c/web-url
                                           (:slug (:org msg))
                                           (:slug board)])
                    message (str "You've been invited to a private board: "
                                 "<" board-url "|" (:name board) ">" )
                    receiver (first (adjust-receiver
                                     {:receiver {
                                        :id (:id slack-info)
                                        :type :user}
                                      :bot {:token token}}))]
                (slack/post-message token
                                    (:id (:receiver receiver))
                                    message)))))))))

(defn- share-entry [token receiver {:keys [org-slug
                                           org-logo-url
                                           org-name
                                           board-name
                                           headline
                                           note
                                           body
                                           comment-count
                                           publisher
                                           published-at
                                           secure-uuid
                                           sharer
                                           auto-share] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (timbre/info "Sending entry share to Slack channel:" receiver)
  (let [channel (:id receiver)
        update-url (s/join "/" [c/web-url org-slug "post" secure-uuid])
        clean-note (when-not (s/blank? note) (str (clean-text note)))
        clean-headline (when-not (s/blank? headline)
                         (clean-text (.text (soup/parse headline))))
        clean-body (when-not (s/blank? body)
                     (clean-text (.text (soup/parse body))))
        update-markdown (if (s/blank? headline) update-url (str "<" update-url "|" clean-headline ">"))
        share-attribution (if (= (:name publisher) (:name sharer))
                            (str "*" (:name sharer) "* shared a post in *" board-name "*")
                            (str "*" (:name sharer) "* shared a post by *" (:name publisher) "* in *" board-name "*"))
        text (if auto-share
              ;; Post automatically shared on publication
              (str "A new post from *" (:name publisher) "* in *" board-name "*: " update-markdown)
              ;; Manual share
              (if clean-note
                (str share-attribution ": " clean-note " — " update-markdown)
                (str share-attribution ": " update-markdown)))
        footer (str "Posted in "
                    board-name
                    " by "
                    (:name publisher)
                    "  |  "
                    (post-date published-at)
                    "  |  "
                    comment-count
                    (if (= "1" comment-count)
                      " comment "
                      " comments ")
                    )
        attachments [{
                      :author_name org-name
                      :author_url org-logo-url
                      :pretext text
                      :title clean-headline
                      :title_link update-url
                      :text clean-body
                      :footer footer
                      :attachment_type "default"
                      :color "good"}]]
    (slack/post-attachments token channel attachments)))

(defn- invite [token receiver {:keys [org-name from from-id first-name url] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (timbre/info "Sending invite to Slack channel:" receiver)
  (let [url-display (last (s/split url #"//"))
        user-prompt (if (s/blank? first-name) "Hey, " (str "Hey " first-name ", "))
        from-person (when-not (s/blank? from) (if from-id (str "<@" from-id "|" from ">") from))
        from-msg (if (s/blank? from-person) "you've been invited to join " (str from-person " would like you to join "))
        org-msg (if (s/blank? org-name) "us " (str "*" org-name "* "))
        full-text (str user-prompt from-msg org-msg "on Carrot at: <" url "|" url-display ">")
        channel (-> msg :receiver :id)]
    (slack/post-message token channel full-text)))

(defn- usage [token receiver]
  {:pre [(string? token)
         (map? receiver)]}
  (timbre/info "Sending usage to Slack channel:" receiver)
  (slack/post-message token (:id receiver) c/usage-message))

(defn- bot-handler [msg]
  {:pre [(or (string? (:type msg)) (keyword? (:type msg)))
         (map? (:receiver msg))
         (string? (-> msg :bot :token))]}
  (let [token (-> msg :bot :token)
        receiver (:receiver msg)
        script-type (keyword (:type msg))]
    (timbre/trace "Routing message with type:" script-type)
    (case script-type
      :share-entry (share-entry token receiver msg)
      :invite (invite token receiver msg)
      :digest (digest/send-digest token receiver msg)
      :usage (usage token receiver)
      (timbre/warn "Ignoring message with script type:" script-type))))

;; ----- Event loop -----

(defn- bot-loop
  "Start a core.async consumer of the bot channel."
  []
  (reset! bot-go true)
  (async/go (while @bot-go
      (timbre/info "Waiting for message on bot channel...")
      (let [msg (<!! bot-chan)]
        (timbre/trace "Processing message on bot channel...")
        (if (:stop msg)
          (do (reset! bot-go false) (timbre/info "Bot stopped."))
          (try
            (if (:Message msg) ;; data change SNS message
              (let [msg-parsed (json/parse-string (:Message msg) true)]
                (when (and ; update or add on a board
                        (or
                          (= (:notification-type msg-parsed) "update")
                          (= (:notification-type msg-parsed) "add"))
                        (= (:resource-type msg-parsed) "board"))
                  (timbre/debug "Received private board notification:")
                  (timbre/debug msg-parsed)
                  (send-private-board-notification msg-parsed)))
              (let [bot-token  (or (-> msg :bot :token) (slack-org/bot-token-for @db-pool (-> msg :receiver :slack-org-id)))
                    _missing_token (if bot-token false (throw (ex-info "Missing bot token for:" {:msg-body msg})))]
                (doseq [m (adjust-receiver msg)]
                  (bot-handler (assoc-in m [:bot :token] bot-token)))))
            (timbre/trace "Processing complete.")
            (catch Exception e
              (timbre/error e))))
        (timbre/trace "Delaying...")
        (Thread/sleep 1000))))) ; 1 second delay, can't hit Slack too aggressively due to rate limits

;; ----- Component start/stop -----

(defn start
 "Stop the core.async bot channel consumer."
  [pool]

  (reset! db-pool pool) ; hold onto the DB pool reference

  (timbre/info "Starting bot...")
  (bot-loop))

(defn stop
 "Stop the core.async bot channel consumer."
  []
  (when @bot-go
    (timbre/info "Stopping bot...")
    (>!! bot-chan {:stop true}))
  (reset! db-pool false))