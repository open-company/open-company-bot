(ns oc.bot.storage
  "Get list of sections from the storage service."
  (:require [clojure.walk :refer (keywordize-keys)]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [oc.bot.config :as config]))

(defn- get-post-options
  [token]
  {:headers {"Authorization" (str "Bearer " token)}})

(defn- storage-request-org-url
  [org]
  ;; /orgs/:org-slug
  (str config/storage-server-url
       "/orgs/"
       org))

(defn- get-data
  [request-url token cb]
  (http/get request-url (get-post-options token)
    (fn [{:keys [status headers body error]}]
      (if error
        (timbre/error "Failed, exception is " error)
        (let [parsed-body (json/parse-string body)]
          (cb (keywordize-keys parsed-body)))))))

(defn- link-for [rel links]
  (:href (some #(when (= (:rel %) rel) %) links)))

(defn- board-list [data]
  (timbre/debug "Storage org data:" (:boards data))
  (let [boards (:boards data)]
    (map #([(:name %) (:slug %)] boards))))

(defn board-list-for
  "
  Given a set of team-id's, and a user's JWToken, return the list of available boards for that user in the
  org that corresponds to the team-id.
  "
  [team-ids jwtoken]
  (get-data config/storage-server-url jwtoken
    (fn [data]
      (timbre/debug "Storage slash data:" (-> data :collection :items))
      (let [orgs (-> data :collection :items)
            org (first (filter #(team-ids (:team-id %)) orgs))
            org-url (link-for "item" (:links org))]
          (if org-url
            (get-data (str config/storage-server-url org-url) jwtoken board-list)
            (do
              (timbre/warn "Unable to retrieve board data for:" data) 
              ["General" "general"]))))))