(ns oc.bot.digest
  "
  Namespace to convert an OC digest request into a Slack message with an attachment per post,
  and to send it via Slack.
  "
  (:require [taoensso.timbre :as timbre]
            [clj-time.coerce :as coerce]
            [cheshire.core :as json]
            [jsoup.soup :as soup]
            [oc.lib.text :as text]
            [oc.lib.slack :as slack]
            [oc.bot.image :as image]))

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

(defn- post-as-attachment [daily board-name {:keys [publisher url headline published-at comment-count comment-authors must-see video-id body]}]
  (let [author-name (:name publisher)
        clean-headline (post-headline headline must-see video-id)
        reduced-body (text/truncated-body body)
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
          :author_name author-name
          :author_icon (:avatar-url publisher)
          :title clean-headline
          :title_link url
          :text reduced-body
          :actions [{:type "button"
                     :text "View post"
                     :url url}]}
          timestamp-map)]
    (if (pos? (or comment-count 0))
      (assoc message :text (str reduced-body "\n" (text/attribution 3 comment-count "comment" comment-authors)))
      message)))

(defn- posts-for-board [daily board]
  (let [pretext (clojure.string/trim (:name board))
        attachments (map #(post-as-attachment daily (:name board) %) (:posts board))]
    (concat [(assoc (first attachments) :pretext (str "*" pretext "*"))] (rest attachments))))

(defn- split-attachments
  "Split message attachments into multiple message if over 16kb
   https://api.slack.com/docs/rate-limits"
  [attachments]
  (let [bytes (.getBytes (json/generate-string attachments) "UTF-8")
        byte-count (count bytes)
        byte-limit 10000] ;; 16k is the limit but need to account for HTTP
    (if (> byte-count byte-limit)
      (let [parts-num (quot (count attachments)
                            (inc (quot byte-count byte-limit)))
            parts (partition parts-num parts-num nil attachments)]
        {:intro (first parts) :rest (rest parts)})
      {:intro nil :rest attachments})))

(defn send-digest [token {channel :id :as receiver} {:keys [org-name org-slug logo-url boards] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [intro (str ":coffee: Good morning " (or org-name "Carrot"))
        intro-attachment {:image_url (image/slack-banner-url org-slug logo-url)
                          :text org-name
                          :fallback "Your morning digest"
                          :color "#FA6452"}
        attachments (conj (flatten (map (partial posts-for-board true) boards)) intro-attachment)
        split-attachments (split-attachments attachments)]
    (timbre/info "Sending digest to:" channel " with:" token)
    (if (:intro split-attachments)
      (do
        (slack/post-attachments token
                                channel
                                (:intro split-attachments) intro)
        (doseq [part (:rest split-attachments)]
          (slack/post-attachments token channel part)))
      (slack/post-attachments token channel (:rest split-attachments) intro))))