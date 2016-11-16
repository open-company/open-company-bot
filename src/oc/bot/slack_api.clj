(ns oc.bot.slack-api
  "Make simple (not web socket) Slack Web API HTTP requests and extract the response."
  (:require [aleph.http :as http]
            [manifold.deferred :as d]
            [taoensso.timbre :as timbre]))

(defn slack-api [method params]
  (timbre/info "Making slack request:" method)
  (d/chain
    (http/get (str "https://slack.com/api/" (name method)) {:query-params params :as :json})
    #(if (-> % :body :ok)
        %
        (throw (ex-info "Error from Slack API"
                {:method method :params params
                 :response (select-keys % [:body :status])})))))

(defn get-channels [token]
  (-> @(slack-api :channels.list {:token token}) :body :channels))

(defn get-users [token]
  (-> @(slack-api :users.list {:token token}) :body :members))

(defn get-team-info [token]
  (-> @(slack-api :team.info {:token token}) :body :team))

(defn get-im-channel [token user-id]
  (-> @(slack-api :im.open {:token token :user user-id}) :body :channel :id))

(defn get-websocket-url [token]
  (-> @(slack-api :rtm.start {:token token}) :body :url))

(defn post-message [token channel text]
  (-> @(slack-api :chat.postMessage {:token token
                                     :text text
                                     :channel channel
                                     :unfurl_links false}) :body :ok))

(comment

  (require '[oc.bot.slack-api :as sapi] :reload)

  (def token "xoxb-103298796854-bdmHI5DS6AwWE5AkuR5KqNoR")
  (def user-id "U1B0U2XC7")

  (def im-channel (sapi/get-im-channel token user-id))
  (sapi/post-message token im-channel "Test it.")

  )