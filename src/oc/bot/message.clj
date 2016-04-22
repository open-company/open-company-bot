(ns oc.bot.message
  (:require [clojure.java.io :as io]
            [clojure.set :as cset]
            [clojure.string :as string]
            [stencil.core :as st]
            [stencil.parser :as stp]
            [taoensso.timbre :as timbre]))

(defn file->script-id [file]
  (keyword (string/replace (.getName file) #"\.edn$" "")))

(defn script-files []
  (remove #(.isDirectory %) (file-seq (io/file (io/resource "scripts")))))

;; TODO this should be statically defined in prod
(defn templates []
  (into {} (for [f (script-files)]
             [(file->script-id f) (-> f slurp read-string)])))

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
  ;; TODO we probably want to throw if no messages are found
  (map #(render % script-params) (get-in (templates) [script-id segment-id])))

(comment 
  (messages-for :onboard
                [:company-name :init]
                {:name "Tom" :company-name "Live.ly" :company-dashboard "/xxx" :contact-person "@tom"})

  )