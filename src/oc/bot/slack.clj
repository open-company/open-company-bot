(ns oc.bot.slack
  "Handle a web socket streaming discussion with the Slack real-time API."
  (:require [clojure.string :as s]
            [aleph.http :as http]
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
  (timbre/info "Pinging Slack...")
  (stream/put! conn (chesire/generate-string {:type "ping" :id id})))

(defn add-id-and-jsonify [out id msg]
  {:pre [(number? id)
         (map? msg)
         (string? (:type msg))
         (or (nil? (:text msg)) (string? (:text msg)))]}
  (let [text (:text msg)
        msg' (assoc msg :id id)]
    (cond 
      (and text (> (count text) 4000)) (throw (ex-info "Refusing to send large message over Slack limit." {:msg msg}))
      (and text (s/blank? text)) (throw (ex-info "Refusing to send blank message, not allowed by Slack." {:msg msg}))
      :else (do (timbre/info "Sending to Slack:" msg')
                (stream/put! out (chesire/generate-string msg'))))))

(defn parse [msg out]
  (timbre/trace "Event received from Slack.")
  (let [m (chesire/parse-string msg keyword)]
    (timbre/trace "Event from Slack:" m)
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
          out-proxy  (stream/stream 5000) ; buffered stream for outgoing messages
          in-proxy   (stream/stream 1000) ; buffered stream for incoming messages
          ;; TODO the keep alive routine should also check if the connection is open and create a new connection if necessary
          keep-alive (t/every 3000 #(send-ping! conn (swap! msg-idx inc)))]
      
      ;; Send outgoing messages via function that handles ID passing and incrementing and JSON and size limits
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

(defn initialize-connection! 
  "Create a Slack RTM API web socket connection for the specified bot token and assoc it by bot token into the
  connections atom."
  [^SlackConnectionManager conn-man bot-token]
  (timbre/info "Initializing new Slack RTM web socket connection for" bot-token)
  (let [c (component/start (slack-connection bot-token))]
    (swap! (:connections conn-man) assoc bot-token c)
    c))

(defn connection-for
  "If there is a Slack RTM API web socket for the specified bot token already in the connections atom, return it."
  [^SlackConnectionManager conn-man bot-token]
  (-> conn-man :connections deref (get bot-token)))

;; REPL stuff =========================================================

(comment
  (def bot
    (map->SlackConnection {:ws-url (get-websocket-url c/slack-bot-token)}))

  (alter-var-root #'bot component/start)

  (alter-var-root #'bot component/stop)

  )