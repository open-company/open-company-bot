(ns oc.bot.storage
  "Get list of sections from the storage service."
  (:require [clojure.walk :refer (keywordize-keys)]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [oc.bot.config :as config]))

(def default-on-error ["General" "general"])

(defn- get-post-options
  [token]
  {:headers {"Authorization" (str "Bearer " token)}})

(defn- storage-request-org-url
  [org]
  ;; /orgs/:org-slug
  (str config/storage-server-url "/orgs/" org))

(defn- get-data
  [request-url token]
  (let [response (http/get request-url (get-post-options token))
        status (:status response)
        success? (= status 200)]
    (timbre/trace "HTTP GET Response:\n" response)
    (if success?
      (-> (:body response) json/parse-string keywordize-keys)
      (timbre/error "HTTP GET failed (" status "):" response))))

(defn- link-for [rel links]
  (:href (some #(when (= (:rel %) rel) %) links)))

(defn- board-list [data]
  (timbre/debug "Storage org data:" (:boards data))
  (->> (:boards data)
    (map #(select-keys % [:name :slug]))
    (remove #(= (:slug %) "drafts"))
    vec))

(defn board-list-for
  "
  Given a set of team-id's, and a user's JWToken, return the list of available boards for that user in the
  org that corresponds to the team-id.
  "
  [team-ids jwtoken]
  (if-let [body (get-data config/storage-server-url jwtoken)]
    (do
      (timbre/debug "Storage slash data:" (-> body :collection :items))
      (let [orgs (-> body :collection :items)
            org (first (filter #(team-ids (:team-id %)) orgs))
            org-url (link-for "item" (:links org))]
        (if org-url
          (board-list (get-data (str config/storage-server-url org-url) jwtoken))
          (do
            (timbre/warn "Unable to retrieve board data for:" team-ids "in:" body) 
            default-on-error))))
    (do
      (timbre/warn "Unable to retrieve org data.") 
      default-on-error)))