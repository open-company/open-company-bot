(ns oc.bot
  (:gen-class)
  (:require [clojure.string :as s]
            [clojure.core.async :as async :refer (<!! >!!)]
            [cuerdas.core :as str]
            [manifold.stream :as stream]
            [com.stuartsierra.component :as component]
            [amazonica.aws.sqs :as aws-sqs]
            [taoensso.timbre :as timbre]
            [clj-time.format :as format]
            [oc.lib.sentry-appender :as sentry]
            [oc.lib.sqs :as sqs]
            [oc.bot.slack-api :as slack-api]
            [oc.bot.config :as c]))

(def bot-chan (async/chan 10000)) ; buffered channel to protect Slack from too many requests

(def iso-format (format/formatters :date-time)) ; ISO 8601
(def link-format (format/formatter "YYYY-MM-dd")) ; Format for date in URL of stakeholder-update links

(defn- system
  "Define our system. There are 2 components of our system, an SQS listener, and a Slack connection manager."
  [config-options]
  (let [{:keys [sqs-creds sqs-queue-url sqs-msg-handler]} config-options]
    (component/system-map
      :sqs (sqs/sqs-listener sqs-creds sqs-queue-url sqs-msg-handler))))

(defn- slack-handler [conn msg-idx msg] (prn msg))

(defn- real-user? [user]
  (and (not (:deleted user))
       (not (:is_bot user))
       (not (:is_restricted user))
       (not (= "USLACKBOT" (:id user)))))

(defn- first-name [name]
  (first (clojure.string/split name #"\s")))

(defn- adjust-receiver
  "Inspect the receiver field and return one or more initialization messages
   with proper DM channels and update :user/name script param."
  [msg]
  (let [token (-> msg :bot :token)
        type  (-> msg :receiver :type)]
    (timbre/info "Adjusting receiver" {:type type})
    (cond
      ;; Directly to a specific user
      (and (= :user type) (s/starts-with? (-> msg :receiver :id) "slack-U"))
      [(assoc msg :receiver {:id (slack-api/get-im-channel token (last (s/split (-> msg :receiver :id) #"-")))
                             :type :channel})]
      
      ;; To a specific channel
      (and (= :channel type))
      [(assoc msg :receiver {:id (-> msg :receiver :id)
                             :type :channel})]

      ;; To every full member of the Slack org (fan out)
      (and (= :all-members type))
      (for [u (filter real-user? (slack-api/get-users token))]
        (-> (assoc-in msg [:script :params :user/name] (first-name (:real_name u)))
            (assoc :receiver {:type :channel :id (slack-api/get-im-channel token (:id u))})))

      :else
      (throw (ex-info "Failed to adjust receiver" {:msg msg})))))

(defn sqs-handler
  "Handle an incoming SQS message to the bot."
  [sys msg]
  {:pre [(:body msg)]}
  (let [msg-body (read-string (:body msg))
        error (if (:test-error msg-body) (/ 1 0) false) ; a message testing Sentry error reporting
        bot-token  (-> msg-body :bot :token)
        _missing_token (if bot-token false (throw (ex-info "Message body missing bot token." {:msg-body msg-body})))]
    (timbre/infof "Received message from SQS: %s\n" msg-body)
    (doseq [m (adjust-receiver msg-body)]
      (>!! bot-chan m))) ; send the message to the bot's channel
  msg)

(defn- send-stakeholder-update [token channel msg]
  {:pre [(string? token)
         (string? channel)
         (map? msg)
         (map? (:script msg))
         (map? (-> msg :script :params))
         (string? (-> msg :script :params :company/slug))
         (string? (-> msg :script :params :stakeholder-update/slug))
         (string? (-> msg :script :params :stakeholder-update/created-at))
         (string? (-> msg :script :params :env/origin))]}
  (timbre/info "Sending stakeholder update message to Slack channel:" channel)
  (let [params (-> msg :script :params)
        origin-url (:env/origin params)
        company-slug (:company/slug params)        
        update-slug (:stakeholder-update/slug params)
        created-at (format/parse iso-format (:stakeholder-update/created-at params))
        update-time (format/unparse link-format created-at)
        user-name (:user/name params)
        user-prompt (if user-name (str " " user-name ", ") ", ")
        company-name (:company/name params)
        company-prompt (if company-name (str " for " company-name " ") " ")
        note (:stakeholder-update/note params)
        clean-note (when note (-> note ; remove HTML
                                (s/replace #"&nbsp;" " ")
                                (str/strip-tags)
                                (str/strip-newlines)))
        channel (-> msg :receiver :id)
        update-url (s/join "/" [origin-url company-slug "updates" update-time update-slug])
        basic-text (str "Hey" user-prompt "I’ve got great news. The newest stakeholder update for" company-prompt
                        "is available at " update-url)
        full-text (if (s/blank? note) basic-text (str basic-text "\n> " clean-note))]
    (slack-api/post-message token channel full-text)))

(defn- bot-handler [msg]
  {:pre [(keyword? (-> msg :script :id))
         (string? (-> msg :receiver :id))
         (string? (-> msg :bot :token))]}       
  (let [token (-> msg :bot :token)
        channel (-> msg :receiver :id)
        script-id (-> msg :script :id)]
    (timbre/trace "Routing message with script ID:" script-id)
    (if (= script-id :stakeholder-update)
      (send-stakeholder-update token channel msg)
      (timbre/warn "Ignoring message with script ID:" script-id))))

;; Consume the bot channel
(async/go
  (while true
    (timbre/info "Waiting for message on bot channel...")
    (let [m (<!! bot-chan)]
      (timbre/trace "Processing message on bot channel...")
      (try
        (bot-handler m)
        (timbre/trace "Processing complete.")
        (catch Exception e
          (timbre/error e)))
      (timbre/trace "Delaying...")
      (Thread/sleep 1000)))) ; 1 second delay, can't hit Slack too aggressively due to rate limits

(defn -main []

  ;; Log errors go to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level (keyword c/log-level)
       :appenders {:sentry (sentry/sentry-appender c/dsn)}})
    (timbre/merge-config! {:level (keyword c/log-level)}))

  ;; Uncaught exceptions go to Sentry
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (timbre/error ex "Uncaught exception on" (.getName thread) (.getMessage ex)))))

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (clojure.java.io/resource "oc/assets/ascii_art.txt")) "\n"))
    "OpenCompany Bot Service\n\n"
    "AWS SQS queue: " c/aws-sqs-bot-queue "\n"
    "AWS API endpoint: " c/oc-api-endpoint "\n"
    "Sentry: " (or c/dsn "false") "\n\n"
    "Ready to serve...\n"))

  ;; Start the system, which will start long polling SQS
  (component/start (system {:sqs-queue-url c/aws-sqs-bot-queue
                            :sqs-msg-handler sqs-handler
                            :sqs-creds {:access-key c/aws-access-key-id
                                        :secret-key c/aws-secret-access-key}}))

  (deref (stream/take! (stream/stream)))) ; block forever