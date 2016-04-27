(ns oc.bot.slack
  (:require [oc.bot.conversation :as conv]
            [aleph.http :as http]
            [com.stuartsierra.component :as component]
            [clojure.string :as string]
            [taoensso.timbre :as timbre]
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
    (when (:ok response)
      (:url response))))

(defn send-ping!
  "Send a ping message to the Slack RTM API to make sure the connection stays alive
   see: https://api.slack.com/rtm#ping_and_pong"
  [conn id]
  ;; Add some error handling/logging
  (s/put! conn (chesire/generate-string {:type "ping" :id id})))

(defn add-id-and-jsonify [out id msg]
  (timbre/info "Sending to Slack:" msg)
  (->> (assoc msg :id id)
       (chesire/generate-string)
       (s/put! out)))

(defn parse [msg out]
  (let [m (chesire/parse-string msg keyword)]
    (timbre/info m)
    (when-not (get m :ok ::ok)
      (timbre/warn "Error event from Slack" m))
    (s/put! out m)))

(defrecord SlackConnection [ws-url]
  component/Lifecycle
  (start [component]
    (timbre/infof "Starting SlackConnection %s\n" ws-url)
    (assert ws-url "Websocket URL required to establish connection")
    (let [msg-idx    (atom 0) ; messages need pos-int ids for same conn
          conn       @(aleph.http/websocket-client ws-url)
          out-proxy  (s/stream)
          in-proxy   (s/stream)
          ;; TODO the keep alive routine should also check if the connection is open and create a new connection if necessary
          keep-alive (t/every 5000 #(send-ping! conn (swap! msg-idx inc)))]
      ;; TODO use s/transform here to generate new sources (maybe)
      (s/connect-via out-proxy #(add-id-and-jsonify conn (swap! msg-idx inc) %) conn)
      (s/connect-via conn #(parse % in-proxy) in-proxy)
      (s/on-closed conn #(timbre/infof "SlackConnection closed %s\n" ws-url))
      (assoc component
             :msg-idx msg-idx
             :conn conn
             :in-proxy in-proxy
             :out-proxy out-proxy
             :keep-alive keep-alive
             :conversation-manager (component/start (conv/conversation-manager in-proxy out-proxy)))))

  (stop [component]
    (timbre/infof "Stopping SlackConnection %s\n" ws-url)
    (s/close! (:conn component))
    (s/close! (:in-proxy component))
    (s/close! (:out-proxy component))
    (component/stop (:conversation-manager component))
    ((:keep-alive component))
    (dissoc component :conn :keep-alive :msg-idx :conversation-manager)))

(defn slack-connection
  "Retrieve a websocket connection url using the provided token and return a SlackConnection component"
  [token]
  (map->SlackConnection {:ws-url (get-websocket-url token)}))

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
    (map->SlackConnection {:ws-url (get-websocket-url (e/env :slack-bot-token))}))

  (alter-var-root #'bot component/start)

  (alter-var-root #'bot component/stop)

  ;; (def every-test (t/every 1000 #(send-ping! (:conn bot) 10)))

  ;; (every-test)

  ;; build out into generic slack-fmt w/ support for the various entities
  ;; (defn fmt-user [uid]
  ;;   (str "<@" uid ">"))

  ;; (defn mentioned? [msg uid]
  ;;   (string/includes? (:text msg) (fmt-user uid)))

  ;; (defn trace [x] (prn 'msg-debug x) x)

  ;; (defn reply! [conn idx msg]
  ;;   (->> {:type "message" :id idx :channel (:channel msg)
  ;;         :text (str (fmt-user (:user msg)) ": Hey there!")}
  ;;        (chesire/generate-string)
  ;;        (s/put! conn)))

  ;; (defn handle-msg [conn idx msg]
  ;;   (let [msg (chesire/parse-string msg keyword)]
  ;;     (when (:text msg)
  ;;       (prn :mentioned (mentioned? msg bot-user-id)))
  ;;     (when (and (:text msg) (mentioned? msg bot-user-id))
  ;;       (reply! conn idx msg))
  ;;     (prn msg)))


  )