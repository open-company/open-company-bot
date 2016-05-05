(ns oc.bot.message
  (:require [clojure.java.io :as io]
            [clojure.set :as cset]
            [clojure.string :as string]
            [medley.core :as med]
            [stencil.core :as st]
            [stencil.parser :as stp]
            [taoensso.timbre :as timbre]))

(def scripts [:onboard :onboard-user :onboard-user-authenticated :stakeholder-update])

(def script-files
  (zipmap scripts (map #(str "scripts/" (name %) ".edn") scripts)))

;; TODO this should be statically defined in prod
(defn- templates []
  (into {} (for [[id r] script-files]
             [id (-> r io/resource slurp read-string)])))

(defn- humanize [v]
  (let [mapping {:oc.bot.conversation/eur "EUR (€)"
                 :oc.bot.conversation/usd "USD ($)"}]
    (or (get mapping v) v)))

(defn- validate-params [parsed params]
  (let [required (->> parsed
                      (filter #(or (instance? stencil.ast.EscapedVariable %)
                                   (instance? stencil.ast.UnescapedVariable %)))
                      (map (comp first :name)))]
    ;; (not-any? (comp nil? params) required)
    (if (cset/subset? (set required) (set (keys params)))
      params
      (throw (ex-info "Supplied params were incomplete"
                      {:required required
                       :params params
                       :parsed-template parsed})))))

(defn- render [tpl params]
  (let [parsed (stp/parse tpl)]
    (->> (med/map-vals humanize params)
         (validate-params parsed)
         (st/render parsed))))

(defn- get-messages
  "Find messages for `segment-id` ([stage transition] tuple)
   in the script `script-id` within the `templates` map."
  [templates script-id segment-id]
  (let [segment (get-in templates [script-id segment-id])]
    (if-not segment
      (throw (ex-info (str "No messages found for " [script-id segment-id])
                      {:templates templates :script-id script-id :segment-id segment-id}))
      (map (fn [m] (if (vector? m) (rand-nth m) m)) segment))))

;; Public API ==================================================================

(defn messages-for [script-id segment-id script-params]
  (timbre/debugf "Getting messages for %s :: %s\n" script-id segment-id)
  (map #(render % script-params) (get-messages (templates) script-id segment-id)))

(comment 
  (messages-for :onboard
                [:company/name :init]
                {:user/name "Tom" :company/name "Live.ly" :company/dashboard "/xxx" :contact-person "@tom"})

  )