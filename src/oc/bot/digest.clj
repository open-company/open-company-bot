(ns oc.bot.digest
  "
  Namespace to convert an OC digest request into a Slack message with an attachment per post,
  and to send it via Slack.
  "
  (:require [taoensso.timbre :as timbre]
            [clj-time.coerce :as coerce]
            [oc.lib.text :as text]
            [oc.lib.slack :as slack]
            [jsoup.soup :as soup]))

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

(defn- post-as-attachment [board-name {:keys [publisher url headline published-at comment-count comment-authors must-see video-id]}]
  (let [author-name (:name publisher)
        clean-headline (post-headline headline must-see video-id)
        ts (-> published-at ; since Unix epoch timestamp for Slack
              (coerce/to-long)
              (/ 1000)
              (int))
        message {
          :fallback (str "A post in " board-name " by " author-name ", '" clean-headline "'.")
          :color "#FA6452"
          :author_name author-name
          :author_icon (:avatar-url publisher)
          :title clean-headline
          :title_link url
          :ts ts}]
    (if (pos? comment-count)
      (assoc message :text (text/attribution 3 comment-count "comment" comment-authors))
      message)))

(defn- posts-for-board [board]
  (let [pretext (:name board)
        attachments (map #(post-as-attachment (:name board) %) (:posts board))]
    (concat [(assoc (first attachments) :pretext pretext)] (rest attachments))))

(defn send-digest [token {channel :id :as receiver} {:keys [digest-frequency org-name boards] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
    (let [frequency (if (= (keyword digest-frequency) :daily) "Yesterday" "Last week")
          intro (if (seq org-name)
                  (str frequency " at " org-name)
                  (str frequency " on Carrot"))
          attachments (flatten (map posts-for-board boards))]
      (timbre/info "Sending digest to:" channel " with:" token)
      (slack/post-message token channel intro)
      (slack/post-attachments token channel attachments)))