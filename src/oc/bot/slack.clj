(ns oc.bot.slack
  (:require [aleph.http :as http]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [manifold.stream :as stream]
            [manifold.time :as t]
            [cheshire.core :as chesire]
            [oc.bot.slack-api :as slack-api]
            [oc.bot.conversation :as conv]))

(defn send-ping!
  "Send a ping message to the Slack RTM API to make sure the connection stays alive
   see: https://api.slack.com/rtm#ping_and_pong"
  [conn id]
  ;; Add some error handling/logging
  (timbre/info "Pinging Slack")
  (stream/put! conn (chesire/generate-string {:type "ping" :id id})))

(defn add-id-and-jsonify [out id msg]
  (let [msg' (assoc msg :id id)]
    (timbre/info "Sending to Slack:" msg')
    (stream/put! out (chesire/generate-string msg'))))

(defn parse [msg out]
  (timbre/info "Event from Slack")
  (let [m (chesire/parse-string msg keyword)]
    (when-not (get m :ok ::ok)
      (timbre/error "Error event from Slack" m))
    (stream/put! out m)))

(defrecord SlackConnection [ws-url]
  component/Lifecycle

  (start [component]
    
    (timbre/info "Starting SlackConnection" ws-url)
    (assert ws-url "Websocket URL required to establish connection")
    
    (let [msg-idx    (atom 0) ; messages need pos-int ids for same conn
          conn       @(http/websocket-client ws-url) ; websocket connection to Slack
          out-proxy  (stream/stream) ; stream for incoming messages
          in-proxy   (stream/stream) ; stream for outgoing messages
          ;; TODO the keep alive routine should also check if the connection is open and create a new connection if necessary
          keep-alive (t/every 5000 #(send-ping! conn (swap! msg-idx inc)))]
      
      (println "Here!")

      ;; Send outgoing messages via function that handles ID passing and incrementing and JSON
      (stream/connect-via out-proxy #(add-id-and-jsonify conn (swap! msg-idx inc) %) conn)
      ;; Receive incoming messages via function that parses JSON and checks for errors
      (stream/connect-via conn #(parse % in-proxy) in-proxy)
      
      ;; Log closing of the connection
      (stream/on-closed conn #(timbre/info "SlackConnection closed" ws-url))
      
      ;; Component state
      (assoc component
             :msg-idx msg-idx
             :conn conn
             :in-proxy in-proxy
             :out-proxy out-proxy
             :keep-alive keep-alive
             :conversation-manager (component/start (conv/conversation-manager in-proxy out-proxy)))))

  (stop [component]
    (timbre/info "Stopping SlackConnection" ws-url)
    (stream/close! (:conn component))
    (stream/close! (:in-proxy component))
    (stream/close! (:out-proxy component))
    (component/stop (:conversation-manager component))
    ((:keep-alive component))
    (dissoc component :conn :keep-alive :msg-idx :conversation-manager)))

(defn slack-connection
  "Retrieve a websocket connection url using the provided token and return a SlackConnection component"
  [token]
  (map->SlackConnection {:ws-url (slack-api/get-websocket-url token)}))

(defrecord SlackConnectionManager []
  component/Lifecycle
  
  (start [component]
    (timbre/info "Starting SlackConnectionManager")
    (assoc component :connections (atom {})))
  
  (stop [component]
    (timbre/info "Stopping SlackConnectionManager")
    (doseq [c (vals @(:connections component))]
      (component/stop c))
    (dissoc component :connections)))

(defn slack-connection-manager []
  (map->SlackConnectionManager {}))

(defn initialize-connection! [^SlackConnectionManager conn-man bot-token]
  (let [c (component/start (slack-connection bot-token))]
    (swap! (:connections conn-man) assoc bot-token c)
    c))

(defn connection-for [^SlackConnectionManager conn-man bot-token]
  (-> conn-man :connections deref (get bot-token)))

;; REPL stuff =========================================================

(comment
  (def bot
    (map->SlackConnection {:ws-url (get-websocket-url c/slack-bot-token)}))

  (alter-var-root #'bot component/start)

  (alter-var-root #'bot component/stop)

  )