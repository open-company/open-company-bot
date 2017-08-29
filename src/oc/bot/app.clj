(ns oc.bot.app
  (:gen-class)
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

;; ----- Bot Request handling -----

(defn- adjust-receiver
  "Inspect the receiver field and return one or more initialization messages
   with proper DM channels and update :user/name script param."
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
  {:pre [(:body msg)]}
  (let [msg-body (read-string (:body msg))
        error (if (:test-error msg-body) (/ 1 0) false) ; a message testing Sentry error reporting
        bot-token  (-> msg-body :bot :token)
        _missing_token (if bot-token false (throw (ex-info "Message body missing bot token." {:msg-body msg-body})))]
    (timbre/infof "Received message from SQS: %s\n" msg-body)
    (doseq [m (adjust-receiver msg-body)]
      (>!! bot-chan m))) ; send the message to the bot's channel
  (sqs/ack done-channel msg))

(defn- share-snapshot [token receiver {:keys [org-slug org-name org-logo-url title note secure-uuid]}]
  {:pre [(string? token)
         (map? receiver)]}
  (timbre/info "Sending snapshot share to Slack channel:" receiver)
  (let [user-name nil ; TODO don't have this yet
        user-prompt (if (and user-name (:db receiver))
                      (str "Hey " user-name ", check it out! Here's the latest update")
                      "Hey, check it out! Here's the latest update")
        org-prompt (if (s/blank? org-name) " " (str " from " org-name))
        clean-note (when note (-> note ; remove HTML
                                (s/replace #"&nbsp;" " ")
                                (str/strip-tags)
                                (str/strip-newlines)))
        channel (:id receiver)
        update-url (s/join "/" [c/web-url org-slug "story" secure-uuid])
        update-markdown (if (s/blank? title) update-url (str "<" update-url "|" title ">"))
        basic-text (str user-prompt org-prompt
                        ": " update-markdown)
        full-text (if (s/blank? note) basic-text (str basic-text "\n> " clean-note))]
    (slack/post-message token channel full-text)))

; (defn- invite [token channel msg]
;   {:pre [(string? token)
;          (string? channel)
;          (map? msg)
;          (map? (:script msg))
;          (map? (-> msg :script :params))
;          (string? (-> msg :script :params :from))
;          (or (string? (-> msg :script :params :from-id))
;              (nil? (-> msg :script :params :from-id)))
;          (string? (-> msg :script :params :org-name))
;          (string? (-> msg :script :params :first-name))
;          (string? (-> msg :script :params :url))]}
;   (timbre/info "Sending invite to Slack channel:" channel)
;   (let [params (-> msg :script :params)
;         org-name (:org-name params)
;         from (:from params)
;         from-id (:from-id params)
;         first-name (:first-name params)
;         url (:url params)
;         url-display (last (s/split url #"//"))
;         user-prompt (if (s/blank? first-name) "Hey, " (str "Hey " first-name ", "))
;         from-person (when-not (s/blank? from) (if from-id (str "<@" from-id "|" from ">") from))
;         from-msg (if (s/blank? from-person) "you've been invited to join " (str from-person " would like you to join "))
;         org-msg (if (s/blank? org-name) "us " (str "*" org-name "* "))
;         full-text (str user-prompt from-msg org-msg "on OpenCompany at: <" url "|" url-display ">")
;         channel (-> msg :receiver :id)]
;     (slack/post-message token channel full-text)))

(defn- bot-handler [msg]
  {:pre [(string? (-> msg :type))
         (map? (-> msg :receiver))
         (string? (-> msg :bot :token))]}
  (let [token (-> msg :bot :token)
        receiver (-> msg :receiver)
        script-type (-> msg :type)]
    (timbre/trace "Routing message with type:" script-type)
    (case script-type
      "share-snapshot" (share-snapshot token receiver msg)
      (timbre/warn "Ignoring message with script type:" script-type))))

;; ----- System Startup -----

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

(defn- stop-bot
 "Stop the core.async bot channel consumer."
  []
  (when @bot-go
    (timbre/info "Stopping bot...")
    (>!! bot-chan {:stop true})))

(defrecord BotChannelConsumer [bot]
  component/Lifecycle
  (start [component]
    (timbre/info "[bot] starting")
    (bot-loop)
    (assoc component :bot true))
  (stop [{:keys [bot] :as component}]
    (if bot
      (do
        (stop-bot)
        (dissoc component :bot))
      component)))

(defn system
  "Define our system that has 2 components: the SQS listener, and the Bot channel consumer."
  [config-options]
  (let [{:keys [sqs-creds sqs-queue sqs-msg-handler]} config-options]
    (component/system-map
      :bot (component/using (map->BotChannelConsumer {}) [])
      :sqs (sqs/sqs-listener sqs-creds sqs-queue sqs-msg-handler))))

(defn echo-config []
  (println (str "\n"
    "AWS SQS queue: " c/aws-sqs-bot-queue "\n"
    "AWS API endpoint: " c/oc-api-endpoint "\n"
    "Web URL: " c/web-url "\n"
    "Sentry: " (or c/dsn "false") "\n\n"
    (when c/intro? "Ready to serve...\n"))))

(defn start
  "Start an instance of the Bot service."
  []

  ;; Log errors go to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level (keyword c/log-level)
       :appenders {:sentry (sa/sentry-appender c/dsn)}})
    (timbre/merge-config! {:level (keyword c/log-level)}))

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (clojure.java.io/resource "oc/assets/ascii_art.txt")) "\n"))
    "OpenCompany Bot Service\n"))
  (echo-config)

  ;; Start the system, which will start long polling SQS
  (component/start (system {:sqs-queue c/aws-sqs-bot-queue
                            :sqs-msg-handler sqs-handler
                            :sqs-creds {:access-key c/aws-access-key-id
                                        :secret-key c/aws-secret-access-key}}))

  (deref (stream/take! (stream/stream)))) ; block forever

(defn -main []
  (start))