(ns oc.bot.async.bot
  "Consume bot requests from SQS, adjust them for our use, and then do the needed bot operation."
  (:require [clojure.string :as s]
            [clojure.core.async :as async :refer (<!! >!!)]
            [cuerdas.core :as str]
            [manifold.stream :as stream]
            [com.stuartsierra.component :as component]
            [amazonica.aws.sqs :as aws-sqs]
            [taoensso.timbre :as timbre]
            [clj-time.format :as format]
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

(defn sqs-handler
  "Handle an incoming SQS message to the bot."
  [msg done-channel]
  (let [msg-body (read-string (:body msg))
        error (if (:test-error msg-body) (/ 1 0) false) ; a message testing Sentry error reporting
        bot-token  (or (-> msg-body :bot :token) (slack-org/bot-token-for @db-pool (-> msg-body :receiver :slack-org-id)))
        _missing_token (if bot-token false (throw (ex-info "Missing bot token for:" {:msg-body msg-body})))]
    (timbre/infof "Received message from SQS: %s\n" msg-body)
    (doseq [m (adjust-receiver msg-body)]
      (>!! bot-chan (assoc-in m [:bot :token] bot-token)))) ; send the message to the bot's channel
  (sqs/ack done-channel msg))

;; ----- Bot Request handling -----

(defn- share-entry [token receiver {:keys [org-slug org-logo-url board-name headline note
                                           publisher secure-uuid sharer auto-share] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (timbre/info "Sending entry share to Slack channel:" receiver)
  (let [channel (:id receiver)
        update-url (s/join "/" [c/web-url org-slug "post" secure-uuid])
        clean-note (when-not (s/blank? note) (str (clean-text note)))
        clean-headline (when-not (s/blank? headline) (clean-text headline))
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
                (str share-attribution ": " update-markdown)))]
    (slack/post-message token channel text)))

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
      (let [m (<!! bot-chan)]
        (timbre/trace "Processing message on bot channel...")
        (if (:stop m)
          (do (reset! bot-go false) (timbre/info "Bot stopped."))
          (try
            (bot-handler m)
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