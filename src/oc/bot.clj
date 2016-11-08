(ns oc.bot
  (:gen-class)
  (:require [clojure.string :as s]
            [com.stuartsierra.component :as component]
            [amazonica.aws.sqs :as aws-sqs]
            [manifold.stream :as stream]
            [taoensso.timbre :as timbre]
            [oc.lib.sentry-appender :as sentry]
            [oc.lib.sqs :as sqs]
            [oc.bot.slack-api :as slack-api]
            [oc.bot.config :as c]
            [oc.bot.slack :as slack]
            [oc.bot.conversation :as conv]
            [oc.bot.message :as msg]))

(defn system [config-options]
  (let [{:keys [sqs-queue sqs-msg-handler]} config-options]
    (component/system-map
      :slack (slack/slack-connection-manager)
      :sqs   (-> (sqs/sqs-listener sqs-queue sqs-msg-handler)
                 (component/using [:slack])))))

(defn slack-handler [conn msg-idx msg] (prn msg))

(defn real-user? [user]
  (and (not (:deleted user))
       (not (:is_bot user))
       (not (:is_restricted user))
       (not (= "USLACKBOT" (:id user)))))

(defn first-name [name]
  (first (clojure.string/split name #"\s")))

(defn adjust-receiver
  "Inspect the receiver field and return one or more initialization messages
   with proper DM channels and update :user/name script param."
  [msg]
  (let [token (-> msg :bot :token)
        type  (-> msg :receiver :type)]
    (timbre/info "Adjusting receiver" {:type type})
    (cond
      (and (= :user type) (s/starts-with? (-> msg :receiver :id) "slack-U"))
      [(assoc msg :receiver {:id (slack-api/get-im-channel token (last (s/split (-> msg :receiver :id) #"-")))
                             :type :channel})]

      (and (= :all-members type))
      (for [u (filter real-user? (slack-api/get-users token))]
        (-> (assoc-in msg [:script :params :user/name] (first-name (:real_name u)))
            (assoc :receiver {:type :channel :id (slack-api/get-im-channel token (:id u))})))

      :else
      (throw (ex-info "Failed to adjust receiver" {:msg msg})))))

(defn sqs-handler
  "Handle an incoming SQS message to the bot."
  [sys msg]
  (let [msg-body   (read-string (:body msg))
        error (if (:test-error msg-body) (/ 1 0) false) ; test Sentry error reporting
        bot-token  (-> msg-body :bot :token)
        slack-conn (or (slack/connection-for (:slack sys) bot-token)
                       (slack/initialize-connection! (:slack sys) bot-token))
        sink       (stream/stream)]
    (timbre/infof "Received message from SQS: %s\n" msg-body)
    (stream/connect (stream/throttle 1.0 sink) (:in-proxy slack-conn))
    (doseq [m (adjust-receiver msg-body)]
      (stream/put! sink (assoc m :type ::initialize))))
  msg)

(defn -main []

  ;; Log errors go to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level     :info
       :appenders {:sentry (sentry/sentry-appender c/dsn)}})
    (timbre/merge-config! {:level :debug}))

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

  ;; Start the system
  (component/start (system {:sqs-queue c/aws-sqs-bot-queue
                            :sqs-msg-handler sqs-handler}))

  (deref (stream/take! (stream/stream)))) ; block forever

(comment
  (do
    (def bot-user-id "U10AR0H50")
    (def bot-testing-ch "C10A1P4H2")
    (def dm-testing-ch "D108XUAFM")
    (def user-id "U0JSATHT3")

    (def jwt "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyLWlkIjoic2xhY2s6VTA2U0JUWEpSIiwibmFtZSI6IlNlYW4gSm9obnNvbiIsInJlYWwtbmFtZSI6IlNlYW4gSm9obnNvbiIsImF2YXRhciI6Imh0dHBzOlwvXC9zZWN1cmUuZ3JhdmF0YXIuY29tXC9hdmF0YXJcL2Y1YjhmYzFhZmZhMjY2YzgwNzIwNjhmODExZjYzZTA0LmpwZz9zPTE5MiZkPWh0dHBzJTNBJTJGJTJGc2xhY2suZ2xvYmFsLnNzbC5mYXN0bHkubmV0JTJGN2ZhOSUyRmltZyUyRmF2YXRhcnMlMkZhdmFfMDAyMC0xOTIucG5nIiwiZW1haWwiOiJzZWFuQG9wZW5jb21wYW55LmNvbSIsIm93bmVyIjpmYWxzZSwiYWRtaW4iOnRydWUsIm9yZy1pZCI6InNsYWNrOlQwNlNCTUg2MCJ9.9Q8GNBojQ_xXT0lMtKve4fb5Pdh260oc2aUc-wP8dus")

    (defn test-onboard-trigger [name ch-id]
      {:diff     (rand-int 1000)
       :script   {:id :onboard :params {:user/name name :company/name "Buffer Inc." :company/slug "buffer"
                                        :company/description "Save time managing your social media" :company/currency "USD"
                                        :contact-person "Tom"}}
       :api-token jwt
       :receiver {:type :user :id ch-id}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    (defn test-su-trigger [name ch-id]
      {:diff     (rand-int 1000)
       :script   {:id :stakeholder-update :params {:user/name name :company/name "Buffer" :company/slug "buffer" :stakeholder-update/slug "abc" :stakeholder-update/note "We're profitable!!"}}
       :receiver {:type :all-members}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    (defn test-onboard-user-trigger [name ch-id]
      {:diff     (rand-int 1000)
       :script   {:id :onboard-user :params {:user/name name :company/name "Buffer" :company/slug "buffer" :contact-person "Jim"}}
       :receiver {:type :user :id ch-id}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    (defn test-onboard-user-authenticated-trigger [name ch-id]
      {:diff     (rand-int 1000)
       :script   {:id :onboard-user-authenticated :params {:user/name name :company/name "Buffer" :company/slug "buffer"}}
       :receiver {:type :user :id ch-id}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}}))

  (test-onboard-trigger "Martin" user-id)

  (sqs-handler sys {:body (pr-str (test-onboard-trigger "Martin" user-id))})

  (def sys (system {:sqs-queue c/aws-sqs-bot-queue
                    :sqs-msg-handler sqs-handler}))

  (alter-var-root #'sys component/start)

  (alter-var-root #'sys component/stop)

  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) (test-onboard-trigger "Stuart" bot-testing-ch))

  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) (test-onboard-trigger "Stuart" user-id))
  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) (test-su-trigger "Martin" user-id))

  ;; send a test message that will cause an exception
  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) {:test-error true})

  ;; Messages after bot got invited into channel
  {:type "channel_joined", :channel {:creator "U06SBTXJR", :purpose {:value "Discuss development of the OPENcompany platform", :creator "U06SBTXJR", :last_set 1454426238}, :is_channel true, :name "development", :is_member true, :is_archived false, :created 1448888630, :topic {:value "", :creator "", :last_set 0}, :latest {:type "message", :user "U0JSATHT3", :text "<@U06SBTXJR>: can you kick the bot out of <#C10A1P4H2> please?", :ts "1461337454.001157"}, :id "C0FGNSA2V", :unread_count_display 0, :last_read "1461337454.001157", :members ["U06SBTXJR" "U06SQLDFT" "U06STCKLN" "U0J5LK571" "U0JSATHT3" "U10AR0H50"], :is_general false, :unread_count 0}}
  {:user "U10AR0H50", :inviter "U0JSATHT3", :type "message", :subtype "channel_join", :team "T06SBMH60", :text "<@U10AR0H50|transparency> has joined the channel", :channel "C0FGNSA2V", :ts "1461337622.001158"}
  ;; Events after being kicked
  {:type "channel_left", :channel "C10A1P4H2"}
  )

(comment 
  (def names (map :real_name (filter real-user? (slack-api/get-users tkn))))

  (map first-name names)

  (map :real_name (filter real-user? (slack-api/get-users tkn)))

  (set (flatten (map :id (remove :deleted (slack-api/get-users tkn)))))

  (adjust-receiver {:bot {:token tkn}
                    :receiver {:type :all-members}})

  )
