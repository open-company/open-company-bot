(ns oc.bot.message
  (:require [clojure.java.io :as io]
            [clojure.set :as cset]
            [stencil.core :as st]
            [stencil.parser :as stp]
            [taoensso.timbre :as timbre]))

;; TODO this should be statically defined in prod
(defn templates [] (-> "scripts.edn" io/resource slurp read-string))

(defn validate-params [parsed params]
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

(defn render [tpl params]
  (let [parsed (stp/parse tpl)]
    (st/render parsed (validate-params parsed params))))

(defn messages-for [script-id segment-id script-params]
  (timbre/debugf "Getting messages for %s :: %s\n" script-id segment-id)
  (map #(render % script-params) (get-in (templates) [script-id segment-id])))

(comment 
  (messages-for :onboard
                [:company-name :init]
                {:name "Tom" :company-name "Live.ly" :company-dashboard "/xxx" :contact-person "@tom"})

  )