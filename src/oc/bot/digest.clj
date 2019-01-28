(ns oc.bot.digest
  "
  Namespace to convert an OC digest request into a Slack message with an attachment per post,
  and to send it via Slack.
  "
  (:require [taoensso.timbre :as timbre]
            [clj-time.coerce :as coerce]
            [jsoup.soup :as soup]
            [clj-time.format :as time-format]
            [clj-time.core :as t]
            [oc.lib.text :as text]
            [oc.lib.slack :as slack]
            [oc.bot.image :as image]))

(def day-month-date-year (time-format/formatter "EEEE, MMM. dd, YYYY"))

(defn- digest-content-date []
  (time-format/unparse day-month-date-year (t/now)))

(defn post-headline [headline must-see video-id]
  (let [clean-headline (.text (soup/parse headline))] ; Strip out any HTML tags
    (cond
      (and must-see video-id)
      (str "[Must see video] " clean-headline)
      must-see
      (str "[Must see] " clean-headline)
      video-id
      (str "[Video] " clean-headline)
      :else
      clean-headline)))

(defn- post-as-attachment [daily board-name {:keys [publisher url headline published-at comment-count comment-authors must-see video-id]}]
  (let [author-name (:name publisher)
        clean-headline (post-headline headline must-see video-id)
        ts (-> published-at ; since Unix epoch timestamp for Slack
              (coerce/to-long)
              (/ 1000)
              (int))
        timestamp-map (if daily
                        {}
                        {:ts ts})
        message (merge {
          :fallback (str "A post in " board-name " by " author-name ", '" clean-headline "'.")
          :color "#FA6452"
          :author_name (str author-name " in " board-name)
          :author_icon (:avatar-url publisher)
          :title clean-headline
          :title_link url
          :actions [{:type "button"
                     :text "View post"
                     :url url}]}
          timestamp-map)]
    (if (pos? comment-count)
      (assoc message :text (text/attribution 3 comment-count "comment" comment-authors))
      message)))

(defn- posts-for-board [daily board]
  (let [pretext (:name board)
        attachments (map #(post-as-attachment daily (:name board) %) (:posts board))]
    (concat [(assoc (first attachments) :pretext pretext)] (rest attachments))))

(defn send-digest [token {channel :id :as receiver} {:keys [org-name org-slug logo-url boards] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [org (or org-name "Carrot")
        intro (str ":coffee: Your " org " morning digest.")
        banner-text (clojure.string/upper-case
                     (str org " • " (digest-content-date)))
        intro-attachment {:image_url (image/slack-banner-url
                                       org-slug
                                       logo-url
                                       banner-text)
                          :text org
                          :fallback "Your morning digest"
                          :color "#FA6452"}
        attachments (conj (flatten (map (partial posts-for-board true) boards)) intro-attachment)]
    (timbre/info "Sending digest to:" channel " with:" token)
    ;;(slack/post-message token channel intro)
    (slack/post-attachments token channel attachments intro)))