(ns oc.bot.slack-api
  (:require [aleph.http :as http]
            [manifold.deferred :as d]))

;; (defn slack-btn-uri
;;   "Generate a URI suitable for initializing OAuth flow"
;;   []
;;   (let [scopes (clojure.string/join "," (map name (e/env :slack-scopes)))]
;;     (str "https://slack.com/oauth/authorize?scope=" scopes "&client_id=" (e/env :slack-client-id))))

;; (def list-channels-action
;;   "https://slack.com/api/channels.list")

;; (defn get-channels
;;   "Retrieve all channels for the team the given API token is associated with"
;;   [api-token]
;;   (let [response (-> @(http/get list-channels-action {:query-params {:token api-token} :as :json}) :body)]
;;     (when (:ok response) (:channels response))))

(defn slack-api [method params]
  (-> (http/get (str "https://slack.com/api/" (name method))
                {:query-params params :as :json})
      (d/chain #(if (-> % :body :ok)
                  %
                  (throw (ex-info "Error after calling Slack API"
                                  {:method method :params params
                                   :response (select-keys % [:body :status])}))))))

(defn get-users [token]
  (-> @(slack-api :users.list {:token token}) :body :members))

(defn get-team-info [token]
  (-> @(slack-api :team.info {:token token}) :body :team))

(defn get-im-channel [token user-id]
  (-> @(slack-api :im.open {:token token :user user-id}) :body :channel :id))

(defn get-websocket-url [token]
  (-> @(slack-api :rtm.start {:token token}) :body :url))