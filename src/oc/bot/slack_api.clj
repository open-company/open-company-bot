(ns oc.bot.slack-api
  "Make simple (not web socket) Slack Web API HTTP requests and extract the response."
  (:require [aleph.http :as http]
            [manifold.deferred :as d]
            [taoensso.timbre :as timbre]))

(defn slack-api [method params]
  (timbre/info "Making slack request:" method)
  (-> (http/get (str "https://slack.com/api/" (name method))
                {:query-params params :as :json})
      (d/chain #(if (-> % :body :ok)
                  %
                  (throw (ex-info "Error from Slack API"
                                  {:method method :params params
                                   :response (select-keys % [:body :status])}))))))

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