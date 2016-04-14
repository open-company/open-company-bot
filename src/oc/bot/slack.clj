(ns oc.bot.slack
  (:require [aleph.http :as http]
            [com.stuartsierra.component :as component]
            [clojure.string :as string]
            [manifold.stream :as s]
            [manifold.time :as t]
            [manifold.deferred :as d]
            [environ.core :as e]
            [cheshire.core :as chesire]))

(defn slack-btn-uri
  "Generate a URI suitable for initializing OAuth flow"
  []
  (let [scopes (clojure.string/join "," (map name (e/env :slack-scopes)))]
    (str "https://slack.com/oauth/authorize?scope=" scopes "&client_id=" (e/env :slack-client-id))))

;; Test token created in slacks API dev tools
(def bot-testing-ch "C10A1P4H2")
(def test-token "xoxp-6895731204-18894935921-34364210081-7283fdaced")
(def token "xoxb-34365017170-VxjcsIQhNcAW1bHX1YAG4yjf")

(def list-channels-action
  "https://slack.com/api/channels.list")

(def ^:private rtm-socket-url
  "https://slack.com/api/rtm.start")

(defn get-channels
  "Retrieve all channels for the team the given API token is associated with"
  [api-token]
  (let [response (-> @(http/get list-channels-action {:query-params {:token api-token} :as :json}) :body)]
    (when (:ok response) (:channels response))))

(defn get-websocket-url
  "Retrieve a websocket connection URL"
  [bot-token]
  (let [response (-> @(http/get rtm-socket-url {:query-params {:token bot-token :no_unreads true} :as :json}) :body)]
    (when (:ok response) (:url response))))

(defn send-ping!
  "Send a ping message to the Slack RTM API to make sure the connection stays alive
   see: https://api.slack.com/rtm#ping_and_pong"
  [conn id]
  ;; Add some error handling/logging
  (s/put! conn (chesire/generate-string {:type "ping" :id id})))

(defrecord SlackConnection [ws-url]
  component/Lifecycle
  (start [component]
    (println ";; Starting SlackConnection")
    (let [msg-idx    (atom 0) ; messages need pos-int ids for same conn
          conn       @(aleph.http/websocket-client ws-url)
          keep-alive (t/every 5000 #(send-ping! conn (swap! msg-idx inc)))]
      (s/consume (partial (:message-handler component) conn (swap! msg-idx inc)) conn)
      ;; (s/on-closed conn #(prn 'slack-bot-connection-closed))
      (assoc component :msg-idx msg-idx :conn conn :keep-alive keep-alive)))

  (stop [component]
    (println ";; Stopping SlackConnection")
    (s/close! (:conn component))
    ((:keep-alive component))
    (dissoc component :conn :keep-alive :msg-idx)))

(defn slack-connection [message-handler]
  (map->SlackConnection {:ws-url (get-websocket-url token)
                         :message-handler (or message-handler prn)}))

(defn send-message! [slack-conn msg]
  (->> (assoc msg :id (deref (:msg-idx slack-conn)))
       (chesire/generate-string)
       (s/put! (:conn slack-conn))))

;; REPL stuff =========================================================

(comment 
  (def bot-user-id "U10AR0H50") ; retrieve from rethinkdb

  ;; build out into generic slack-fmt w/ support for the various entities
  (defn fmt-user [uid]
    (str "<@" uid ">"))

  (defn mentioned? [msg uid]
    (string/includes? (:text msg) (fmt-user uid)))

  (defn trace [x] (prn 'msg-debug x) x)

  (defn reply! [conn idx msg]
    (->> {:type "message" :id idx :channel (:channel msg)
          :text (str (fmt-user (:user msg)) ": Hey there!")}
         (chesire/generate-string)
         (s/put! conn)))

  (defn handle-msg [conn idx msg]
    (let [msg (chesire/parse-string msg keyword)]
      (when (:text msg)
        (prn :mentioned (mentioned? msg bot-user-id)))
      (when (and (:text msg) (mentioned? msg bot-user-id))
        (reply! conn idx msg))
      (prn msg)))

  (def bot 
    (map->SlackConnection {:ws-url (get-websocket-url token)
                           :message-handler handle-msg}))

  (alter-var-root #'bot component/start)

  (alter-var-root #'bot component/stop)

  (send-ping! (:conn bot) 10)

  (def every-test (t/every 1000 #(send-ping! (:conn bot) 10)))
  
  (every-test)

  )