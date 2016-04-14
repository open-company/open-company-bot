(ns oc.bot
  (:require [aleph.http :as http]
            [com.stuartsierra.component :as component]
            [manifold.stream :as s]
            [manifold.time :as t]
            ;; [gniazdo.core :as ws]
            [manifold.deferred :as d]
            [cheshire.core :as chesire]))

(def slack {:client-id "6895731204.34361347010"
            :client-secret "428af3d287bc111a7619669ea78ced23"
            :scopes [:commands :bot :users:read]})

(defn slack-btn-uri [slack]
  (let [scopes (clojure.string/join "," (map name (:scopes slack)))]
    (str "https://slack.com/oauth/authorize?scope=" scopes "&client_id=" (:client-id slack))))

;; Test token created in slacks API dev tools
(def bot-testing-ch "C10A1P4H2")

(def test-token "xoxp-6895731204-18894935921-34364210081-7283fdaced")

(def token "xoxb-34365017170-VxjcsIQhNcAW1bHX1YAG4yjf")

(def list-channels-action
  "https://slack.com/api/channels.list")

(def ^:private rtm-socket-url
  "https://slack.com/api/rtm.start")

(defn get-channels [api-token]
  (let [response (-> @(http/get list-channels-action {:query-params {:token api-token} :as :json}) :body)]
    (prn response)
    (when (:ok response)
      (:channels response))))

(defn get-websocket-url [api-token]
  (let [response (-> @(http/get rtm-socket-url {:query-params {:token api-token :no_unreads true} :as :json})
                     :body)]
    (when (:ok response)
      (:url response))))

;; (clojure.pprint/pprint (get-channels token))

;; (let [+ws-url+ (get-websocket-url token)]
;;   (def conn
;;     (let [c @(aleph.http/websocket-client +ws-url+)]
;;       (s/consume prn c)
;;       (s/on-closed c #(prn 'slack-bot-connection-closed))
;;       (d/catch c #(str "something unexpected: " (.getMessage %)))
;;       c)))

;; (s/close! conn)

;; (-> (s/put! conn (chesire/generate-string {:type "message"
;;                                            :channel bot-testing-ch
;;                                            :text "Skynet's taking over now"}))
;;     (d/catch #(str "something unexpected: " (.getMessage %))))

(defn send-ping! [conn id]
  ;; Add some error handling/logging
  (s/put! conn (chesire/generate-string {:type "ping" :id id})))

(defrecord SlackConnection [ws-url]
  ;; Implement the Lifecycle protocol
  component/Lifecycle
  (start [component]
    (println ";; Starting SlackConnection")
    (let [msg-idx    (atom 0) ; messages need pos-int ids for same conn
          conn       @(aleph.http/websocket-client ws-url)
          keep-alive (t/every 5000 #(send-ping! conn (swap! msg-idx inc)))]
      (s/consume (:message-handler component) conn)
      (s/on-closed conn #(prn 'slack-bot-connection-closed))
      ;; (d/catch conn #(str "something unexpected: " (.getMessage %))) ; not sure if this actually does anything
      (send-ping! conn (swap! msg-idx inc))
      (assoc component
             :msg-idx msg-idx
             :conn conn
             :keep-alive keep-alive)))

  (stop [component]
    (println ";; Stopping SlackConnection")
    (s/close! (:conn component))
    ((:keep-alive component))
    (dissoc component :conn :keep-alive :msg-idx)))

(comment 
  (def bot 
    (map->SlackConnection {:ws-url (get-websocket-url token)
                    :message-handler prn}))

  (alter-var-root #'bot component/start)

  (alter-var-root #'bot component/stop)

  (send-ping! (:conn bot) 10)

  (def every-test (t/every 1000 #(send-ping! (:conn bot) 10)))
  
  (every-test)

  )


;; Gniazdo - if aleph turns out to be bad ====================================
;; (def socket
;;     (ws/connect
;;      (get-websocket-url token)
;;      :on-receive #(prn 'received %)
;;      :on-error #(prn 'error %)))
 