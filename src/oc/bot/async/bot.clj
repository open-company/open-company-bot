(ns oc.bot.async.bot
  "Consume bot requests from SQS, adjust them for our use, and then do the needed bot operation."
  (:require [clojure.string :as s]
            [clojure.core.async :as async :refer (<!! >!!)]
            [cuerdas.core :as str]
            [oc.lib.sentry.core :as sentry]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [oc.lib.sqs :as sqs]
            [oc.lib.slack :as slack]
            [oc.lib.jwt :as jwt]
            [oc.lib.html :as html]
            [oc.lib.storage :as storage]
            [oc.lib.user :as user]
            [oc.lib.text :as lib-text]
            [oc.bot.digest :as digest]
            [oc.bot.async.slack-action :as slack-action]
            [oc.bot.resources.slack-org :as slack-org]
            [oc.bot.config :as c]
            [oc.bot.lib.text :as text]))

(def db-pool (atom false)) ; atom holding DB pool so it can be used for each SQS message

(def attachment-grey-color "#E8E8E8")
(def attachment-blue-color "#6187F8")

(def iso-format (time-format/formatters :date-time))
(def date-format (time-format/formatter "MMMM d"))
(def date-format-year (time-format/formatter "MMMM d YYYY"))

;; ----- core.async -----

(defonce bot-chan (async/chan 10000)) ; buffered channel to protect Slack from too many requests

(defonce bot-go (atom nil))

;; ----- Utility functions -----

(defn- real-user? [user]
  (and (not (:deleted user))
       (not (:is_bot user))
       (not (:is_restricted user))
       (not= "USLACKBOT" (:id user))))

(defn- first-name [name]
  (first (s/split name #"\s")))


(defn- post-date [timestamp]
  (let [d (time-format/parse iso-format timestamp)
        n (time/now)
        same-year? (= (time/year n) (time/year d))
        output-format (if same-year? date-format date-format-year)]
    (time-format/unparse output-format d)))

(def carrot-explainer "Carrot is the personalized news feed your team is using to stay in sync with fewer interruptions.")

(defn get-post-data [payload]
  (let [notification (:notification payload)
        slack-bot (:bot payload)
        slack-user-id (:slack-user-id (:receiver payload))
        slack-team-id (or (:slack-org-id (:receiver payload))
                          (:slack-org-id slack-bot))
        slack-user-map {:slack-user-id slack-user-id
                        :slack-team-id slack-team-id}
        config {:storage-server-url c/storage-server-url
                :auth-server-url c/auth-server-url
                :passphrase c/passphrase
                :service-name "Bot"}]
    (if (and slack-user-id
             slack-team-id)
      (storage/post-data-for config slack-user-map (:slug (:org payload)) (:board-id notification) (:entry-id notification))
      (throw (ex-info "No Slack info to retrieve post" {:notification notification
                                                        :bot slack-bot
                                                        :slack-user-map slack-user-map})))))

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
                             :slack-user-id (-> msg :receiver :id)
                             :slack-org-id (-> msg :receiver :slack-org-id)
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
  (doseq [msg-body (sqs/read-message-body (:body msg))]
    (let [_error (if (:test-error msg-body) (/ 1 0) false)] ; a message testing Sentry error reporting
      (timbre/info "Received message from SQS")
      (timbre/debugf "Received message from SQS: %s\n" msg-body)
      (>!! bot-chan msg-body))) ; send the message to the bot's channel
  (sqs/ack done-channel msg))

;; ----- Bot Request handling -----

(defn- notification-org-url [msg]
  (let [org (:org msg)
        org-slug (:slug org)]
    (s/join "/" [c/web-url org-slug "home"])))

(defn- notification-entry-url [msg post-data]
  (let [org (:org msg)
        org-slug (:slug org)
        uuid (:uuid post-data)
        board (:board msg)
        board-slug (or (:slug board)
                       (:board-slug post-data)
                       (:uuid board)
                       (:board-uuid post-data))
        secure-uuid (:secure-uuid (:notification msg))
        first-name (:first-name msg)
        token-claims {:org-uuid (:org-id msg)
                      :secure-uuid secure-uuid
                      :name (str first-name " " (:last-name msg))
                      :first-name first-name
                      :last-name (:last-name msg)
                      :user-id (:user-id msg)
                      :avatar-url (:avatar-url msg)
                      :team-id (:team-id org)} ;; Let's read the team-id from the org to avoid problems on multiple org users}
        id-token (jwt/generate-id-token token-claims c/passphrase)
        interaction-id (:interaction-id (:notification msg))
        base-url (if (seq interaction-id)
                   (s/join "/" [c/web-url org-slug board-slug "post" uuid "comment" interaction-id])
                   (s/join "/" [c/web-url org-slug board-slug "post" uuid]))]
    (str base-url "?id=" id-token)))

(defn- text-for-notification
  [{:keys [org notification] :as msg}]
  (let [comment? (:interaction-id notification)
        mention? (:mention? notification)
        entry-publisher (:entry-publisher notification)
        user-id (:user-id notification)
        from (:author notification)]
    (if-not mention?
      (str ":speech_balloon: *" (:name from) "* added a comment:")
      (str ":speech_balloon: " (:name from) " mentioned you:"))))

(defn- board-access-string [board-access]
  (cond
    (= board-access "private")
    " (private)"
    (= board-access "public")
    " (public)"
    :else
    ""))

(defn- vertical-line-color [must-see]
  (if must-see
    attachment-blue-color
    attachment-grey-color))

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
                    note (:note msg)
                    expnote (if (s/blank? note)
                              carrot-explainer
                              note)
                    board-url (s/join "/" [c/web-url
                                           (:slug (:org msg))
                                           (:slug board)])
                    message (str "You've been invited to a private section: "
                                 "<" board-url "|" (:name board) (board-access-string (:access board)) ">"
                                 " on Carrot.\n\n")
                    receiver (first (adjust-receiver
                                     {:receiver {
                                        :id (:id slack-info)
                                        :type :user}
                                      :bot {:token token}}))]
                (slack/post-attachments token
                                        (:id (:receiver receiver))
                                        [{:pretext message :text expnote}])))))))))

(defn- share-entry [token receiver {:keys [org-slug
                                           org-logo-url
                                           org-name
                                           board-name
                                           board-slug
                                           board-access
                                           entry-uuid
                                           headline
                                           abstract
                                           note
                                           body
                                           comment-count
                                           publisher
                                           published-at
                                           secure-uuid
                                           sharer
                                           auto-share
                                           must-see
                                           video-id] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (timbre/info "Sending entry share to Slack channel:" receiver)
  (let [channel (:id receiver)
        update-url (s/join "/" [c/web-url org-slug board-slug "post" entry-uuid])
        clean-note (text/clean-html note)
        clean-headline (text/clean-html headline)
        clean-body (text/clean-html body)
        clean-abstract (text/clean-html abstract)
        reduced-body (lib-text/truncated-body clean-body)
        accessory-image (html/first-body-thumbnail body)
        share-attribution (if (= (:name publisher) (:name sharer))
                            (str "*" (:name sharer) "* shared a post in *" board-name (board-access-string board-access) "*")
                            (str "*" (:name sharer)
                             "* shared a post by *" (:name publisher)
                             "* in *" board-name (board-access-string board-access) "*"))
        text (if auto-share
              ;; Post automatically shared on publication
              (str "A new post from *" (:name publisher) "* in *" board-name
                (board-access-string board-access) "*")
              ;; Manual share
              (str share-attribution))
        footer (when-not auto-share
                 (str (post-date published-at)
                  "  |  "
                  comment-count
                  (if (= "1" comment-count)
                    " comment "
                    " comments ")))
        attachment {:title clean-headline
                    :title_link update-url
                    :text (if (s/blank? clean-abstract) reduced-body clean-abstract)
                    :author_name (:name publisher)
                    :author_icon (:avatar-url publisher)
                    :thumb_url (:thumbnail accessory-image)
                    :footer footer
                    :color (vertical-line-color must-see)
                    :actions [{:text "View post" :type "button" :url update-url}]}
        attachments (if clean-note
                        [{:pretext text :text clean-note} attachment]
                        [(assoc attachment :pretext text)])] ; no optional note provided
    (slack/post-attachments token channel attachments)))

(defn- invite [token receiver {:keys [org-name from from-id first-name url note] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (timbre/info "Sending invite to Slack channel:" receiver)
  (let [url-display (last (s/split url #"//"))
        user-prompt (if (s/blank? first-name) "Hey, " (str "Hey " first-name ", "))
        from-person (when-not (s/blank? from) (if from-id (str "<@" from-id "|" from ">") from))
        from-msg (if (s/blank? from-person) "you've been invited to join " (str from-person " would like you to join "))
        org-msg (if (s/blank? org-name) "us " (str "*" org-name "* "))
        full-text (str user-prompt from-msg org-msg "on Carrot at: <" url "|" url-display ">\n\n")
        channel (-> msg :receiver :id)
        expnote (if (s/blank? note)
                  carrot-explainer
                  note)]
    (slack/post-attachments token channel [{:pretext full-text :text expnote}])))

(defn- usage [token receiver]
  {:pre [(string? token)
         (map? receiver)]}
  (timbre/info "Sending usage to Slack channel:" receiver)
  (slack/post-message token (:id receiver) c/usage-message))

(defn- welcome [token receiver]
  {:pre [(string? token)
         (map? receiver)]}
  (timbre/info "Sending welcome message to Slack channel:" receiver)
  (slack/post-message token (:id receiver) c/welcome-message))

(defn- team-notify [token receiver msg]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (timbre/info "Sending notification for team to Slack channel:" receiver)
  (let [content (text/clean-html (:content (:notification msg)))
        org-url (notification-org-url msg)]
    (slack/post-attachments token
                            (:id receiver)
                            [{:title (:name (:org msg))
                              :title_link org-url
                              :color attachment-grey-color
                              :actions [{:type "button"
                                         :text "Open"
                                         :url (str org-url "?force-refresh-jwt=1")}]}]
                            content)))

(defn- entry-notify [token receiver msg]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (timbre/info "Sending notification for entry to Slack channel:" receiver)
  (let [content (text/clean-html (:content (:notification msg)))
        post-data (get-post-data msg)
        entry-url (notification-entry-url msg post-data)
        comment? (:interaction-id (:notification msg))
        text-for-notification (text-for-notification msg)]
    (slack/post-attachments token
                            (:id receiver)
                            [{:title (:headline post-data)
                              :title_link entry-url
                              :text content
                              :color attachment-grey-color
                              :actions [{:type "button"
                                         :text (if comment? "Reply" "View post")
                                         :url entry-url}]}]
                            text-for-notification)))

(defn- notify [token receiver msg]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (if (:team? (:notification msg))
    (team-notify token receiver msg)
    (entry-notify token receiver msg)))

;; Reminders

(def occurrence-values
  {:weekly {:monday "Monday"
            :tuesday "Tuesday"
            :wednesday "Wednesday"
            :thursday "Thursday"
            :friday "Friday"
            :saturday "Saturday"
            :sunday "Sunday"}
   :biweekly {:monday "Monday"
              :tuesday "Tuesday"
              :wednesday "Wednesday"
              :thursday "Thursday"
              :friday "Friday"
              :saturday "Saturday"
              :sunday "Sunday"}
   :monthly {:first "first day of the month"
             :first-monday "first Monday of the month"
             :last-friday "last Friday of the month"
             :last "last day of the month"}
   :quarterly {:first "first day of the quarter"
               :first-monday "first Monday of the quarter"
               :last-friday "last Friday of the quarter"
               :last "last day of the quarter"}})

(def occurrence-fields
  {:weekly :week-occurrence
   :biweekly :week-occurrence
   :monthly :period-occurrence
   :quarterly :period-occurrence})

(defn occurrence-value [reminder]
  (let [frequency (keyword (:frequency reminder))
        values (frequency occurrence-values)]
    ((keyword ((frequency occurrence-fields) reminder)) values)))

(defn- frequency-copy [reminder]
  (case (s/lower-case (:frequency reminder))
    "weekly"
    (str "Occurs every week on " (occurrence-value reminder) "s.")
    "biweekly"
    (str "Occurs every other week on " (occurrence-value reminder) "s.")
    "monthly"    
    (str "Occurs on the " (occurrence-value reminder ) ".")
    "quarterly"
    (str "Occurs on the " (occurrence-value reminder) ".")))

(defn reminder-notification [token receiver {:keys [org notification] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [reminder (:reminder notification)
        author (:author reminder)
        author-name (or (:name author)
                        (when (and (not (str/blank? (:first-name author)))
                                   (not (str/blank? (:last-name author))))
                          (str (:first-name author) " " (:last-name author)))
                        (:first-name author)
                        "Someone")
        content (str ":clock9: " author-name
                  " created a new reminder for you. ")
        reminders-url (str (s/join "/" [c/web-url (:slug org) "all-posts"]) "?reminders")
        attachment {:text (frequency-copy reminder)
                    :title (str "Reminder: " (:headline reminder))
                    :title_link reminders-url
                    :color attachment-grey-color
                    :actions [{:type "button"
                               :text "View reminder"
                               :url reminders-url}]}]
    (slack/post-attachments token
                            (:id receiver)
                            [attachment]
                            content)))

(defn reminder-alert [token receiver {:keys [org notification] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [reminder (:reminder notification)
        assignee (:assignee reminder)
        first-name (or (:first-name assignee) (first-name (:name assignee)))
        content (str ":clock9: Hi " first-name
                  ", a quick reminder - it's time to share the latest with your team in Carrot.")
        new-post-url (str (s/join "/" [c/web-url (:slug org) "all-posts"]) "?new")
        reminders-url (str (s/join "/" [c/web-url (:slug org) "all-posts"]) "?reminders")
        attachment {:title (str "Reminder: " (:headline reminder))
                    :title_url reminders-url
                    :text (frequency-copy reminder)
                    :color attachment-grey-color
                    :actions [{:type "button"
                               :text "OK, let's do it"
                               :url new-post-url}]}]
    (slack/post-attachments token
                            (:id receiver)
                            [attachment]
                            content)))

(defn- follow-up-notification [token receiver {:keys [org follow-up] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (timbre/info "Sending follow-up notification to Slack channel:" receiver)
  (let [post-data (get-post-data msg)
        follow-up-data (first (filterv #(= (-> % :assignee :user-id) (:user-id msg)) (:follow-ups post-data)))
        clean-body (lib-text/truncated-body (text/clean-html (:body post-data)))
        entry-url (notification-entry-url msg post-data)
        author-name (user/name-for (:author follow-up-data))
        text-for-notification (str ":zap: " author-name " requested you to follow up")]
    (slack/post-attachments token
                            (:id receiver)
                            [{:title (:headline post-data)
                              :title_link entry-url
                              :text clean-body
                              :color attachment-blue-color
                              :actions [{:type "button"
                                         :text "View post"
                                         :url entry-url}]}]
                            text-for-notification)))

;; Messages type handler

(defn- bot-handler [msg]
  {:pre [(or (string? (:type msg)) (keyword? (:type msg)))
         (map? (:receiver msg))
         (string? (-> msg :bot :token))]}
  (let [token (-> msg :bot :token)
        receiver (:receiver msg)
        msg-type (keyword (:type msg))]
    (timbre/trace "Routing message with type:" msg-type)
    (case msg-type
      :share-entry (share-entry token receiver msg)
      :invite (invite token receiver msg)
      :digest (digest/send-digest token receiver msg)
      :usage (usage token receiver)
      :welcome (welcome token receiver)
      :notify (notify token receiver msg)
      :reminder-notification (reminder-notification token receiver msg)
      :reminder-alert (reminder-alert token receiver msg)
      :follow-up (follow-up-notification token receiver msg)
      (timbre/warn "Ignoring message with script type:" msg-type))))

;; ----- Event loop -----

(defn- bot-loop
  "Start a core.async consumer of the bot channel."
  []
  (reset! bot-go true)
  (async/go (while @bot-go
      (timbre/trace "Waiting for message on bot channel...")
      (let [msg (<!! bot-chan)]
        (timbre/debug "Processing message on bot channel...")
        (if (:stop msg)
          (do (reset! bot-go false) (timbre/info "Bot stopped."))
          (try
            (if (:Message msg)
              (let [msg-parsed (json/parse-string (:Message msg) true)
                    notification-type (:notification-type msg-parsed)
                    resource-type (:resource-type msg-parsed)
                    callback-id (:callback_id msg-parsed)]
                (timbre/infof "Processing %s %s message on bot-loop" resource-type notification-type)
                (cond

                  ;; SNS originated about a user updated or added to a board
                  (and (or (= notification-type "update")
                           (= notification-type "add"))
                       (= resource-type "board"))
                  (do
                    (timbre/info "Handling board add/update message")
                    (timbre/trace "Received private board notification:" msg-parsed)
                    (send-private-board-notification msg-parsed))

                  ;; SNS originated about the Post to Carrot action
                  (or (= (:type msg-parsed) "interactive_message")
                      (= callback-id "post")
                      (= callback-id "add_post"))
                  (do
                    (timbre/info "Handling slack action message")
                    (timbre/trace "Received post action notification:" msg-parsed)
                    (slack-action/send-payload! msg-parsed))

                  :else
                  (timbre/debug "Unknown SQS request:" msg-parsed)))

              ; else it's a direct SQS request to send information out using the bot
              (let [bot-token  (or (-> msg :bot :token) (slack-org/bot-token-for @db-pool (-> msg :receiver :slack-org-id)))]
                (when-not bot-token
                  (throw (ex-info "Missing bot token for:" {:msg-body msg})))
                (timbre/infof "Handling direct bot message")
                (doseq [m (adjust-receiver msg)]
                  (bot-handler (assoc-in m [:bot :token] bot-token)))))
            (timbre/trace "Processing complete.")
            (catch Exception e
              (timbre/warn e)
              (sentry/capture e))))))))

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
