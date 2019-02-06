(ns oc.bot.digest
  "
  Namespace to convert an OC digest request into a Slack message with an attachment per post, and to send it via Slack.
  "
  (:require [taoensso.timbre :as timbre]
            [clj-time.coerce :as coerce]
            [jsoup.soup :as soup]
            [cheshire.core :as json]
            [oc.lib.text :as text]
            [oc.lib.slack :as slack]
            [oc.lib.change :as change]
            [oc.bot.image :as image]
            [oc.bot.config :as c]))

(defn get-seen-data [payload entry-id]
  (let [team (:team-id payload)
        slack-bot (:bot payload)
        receiver (:receiver payload)
        slack-user-map {:slack-user-id (:slack-user-id receiver)
                        :slack-team-id (:slack-org-id receiver)}
        config {:change-server-url c/change-server-url
                :auth-server-url c/auth-server-url
                :passphrase c/passphrase
                :service-name "Bot"}]
    (change/seen-data-for config slack-user-map entry-id)))

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

(def seen-text "✓ Seen")

(defn- post-as-attachment [daily board-name {:keys [publisher url headline published-at comment-count comment-authors must-see video-id body uuid]} msg]
  (let [seen-data (get-seen-data msg uuid)
        author-name (:name publisher)
        clean-headline (post-headline headline must-see video-id)
        reduced-body (text/truncated-body body)
        ts (-> published-at ; since Unix epoch timestamp for Slack
              (coerce/to-long)
              (/ 1000)
              (int))
        timestamp-map (if daily
                        {}
                        {:ts ts})
        ;; if read/seen use seen attachment, else use button
        seen-this (some #(= (:user-id msg) (:user-id %))
                        (get-in seen-data [:post :read]))
        seen-attach (if-not seen-this
                      [{:type "button"
                        :text "View post"
                        :url url}]
                      [])
        message (merge {
          :fallback (str "A post in " board-name " by " author-name ", '" clean-headline "'.")
          :color "#FA6452"
          :author_name author-name
          :author_icon (:avatar-url publisher)
          :title clean-headline
          :title_link url
          :text reduced-body
          :footer (when seen-this (json/encode seen-text))
          :actions seen-attach}
          timestamp-map)]
    (timbre/debug seen-attach)
    (if (pos? (or comment-count 0))
      (assoc message :text (str reduced-body "\n" (text/attribution 3 comment-count "comment" comment-authors)))
      message)))

(defn- posts-for-board [daily board msg]
  (let [pretext (clojure.string/trim (:name board))
        attachments (map #(post-as-attachment daily (:name board) % msg) (:posts board))]
    (concat [(assoc (first attachments) :pretext (str "*" pretext "*"))] (rest attachments))))

(defn send-digest [token {channel :id :as receiver} {:keys [org-name org-slug logo-url boards] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [intro (str ":coffee: Good morning " (or org-name "Carrot"))
        intro-attachment {:image_url (image/slack-banner-url org-slug logo-url)
                          :text org-name
                          :fallback "Your morning digest"
                          :color "#FA6452"}
        attachments (conj (flatten (map #(posts-for-board true % msg) boards)) intro-attachment)]
    (timbre/info "Sending digest to:" channel " with:" token)
    ;;(slack/post-message token channel intro)
    (slack/post-attachments token channel attachments intro)))