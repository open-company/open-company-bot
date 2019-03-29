(ns oc.bot.digest
  "
  Namespace to convert an OC digest request into a Slack message with an attachment per post, and to send it via Slack.
  "
  (:require [taoensso.timbre :as timbre]
            [cheshire.core :as json]
            [jsoup.soup :as soup]
            [oc.lib.text :as text]
            [oc.lib.html :as html]
            [oc.lib.slack :as slack]
            [oc.lib.change :as change]
            [oc.bot.image :as image]
            [oc.bot.config :as c]
            [clojure.string :as s]))

(defonce footer-fallbacks [
  "That's all for now!"
  "You're all caught up."
  "Have a great day!"
  "Now you're in sync."
  "Go seize the day."
  "That's a wrap, enjoy the day!"])

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

(defn post-headline [headline]
  (.text (soup/parse headline)))

(def seen-text "✓ You've viewed this post")

(defn- static-image-url [image-name]
  (str c/digest-bot-static-images-url "/slack_images/" image-name))

(defn- slack-escaped-text [text]
  (-> text
   (s/replace #"<" "&lt;")
   (s/replace #">" "&gt;")
   (s/replace #"&" "&amp;")
   (s/replace #"\n" "\n")))

(defn- markdown-post [url headline body]
  (str "<" url "|*" (slack-escaped-text headline) "*>\n" (slack-escaped-text body)))

(defn- post-as-block [{:keys [publisher url headline published-at comment-count comment-authors board-name
                              interaction-attribution must-see video-id body uuid reactions]} msg]
  (let [seen-data (get-seen-data msg uuid)
        author-name (:name publisher)
        clean-headline (post-headline headline)
        reduced-body (text/truncated-body body)
        accessory-image (html/first-body-thumbnail body)
        ;; if read/seen use seen attachment, else use button
        seen-this? (some #(= (:user-id msg) (:user-id %))
                      (get-in seen-data [:post :read]))
        pre-block {:type "context"
                   :elements (remove nil? [
                    {:type "image"
                     :image_url (:avatar-url publisher)
                     :alt_text author-name}
                    {:type "plain_text"
                     :text (str author-name " in " board-name)}
                    (when must-see
                      {:type "image"
                       :image_url (static-image-url "digest_must_see_icon@4x.png")
                       :alt_text "Must See"})
                    (when must-see
                      {:type "plain_text"
                       :text "Must See"})])}
        body-block {:type "section"
                    :text {
                      :type "mrkdwn"
                      :text (markdown-post url clean-headline reduced-body)}}
        body-with-thumbnail (if accessory-image
                             (merge body-block
                              {:accessory {
                                :type "image"
                                :image_url (:thumbnail accessory-image)
                                :alt_text "Thumbnail"}})
                             body-block)
        interaction-block (when (or (pos? (or comment-count 0))
                                    (pos? (or (count reactions) 0)))
                            {:type "context"
                               :elements [
                                {:type "mrkdwn"
                                 :text (str "_" interaction-attribution "_")}]})
        post-block (when seen-this?
                    {:type "context"
                     :elements [
                      {:type "plain_text"
                       :text seen-text}]})
        separator-block {:type "divider"}]
    (remove nil?
     [pre-block
      body-with-thumbnail
      interaction-block
      post-block
      separator-block])))

(defn- sort-must-see-board-name [a b]
  (let [must-see (compare (:must-see a) (:must-see b))]
    (if (zero? must-see)
      (let [board-name (compare (:board-name a) (:board-name b))]
        (if (zero? board-name)
          (compare (:published-at a) (:published-at b))
          board-name))
      must-see)))

(defn- split-blocks
  "Split message blocks into multiple message if over 16kb
   https://api.slack.com/docs/rate-limits"
  [blocks]
  (let [default-split 5 ; 4 posts plus header
        four-split (partition default-split default-split nil blocks)
        four-bytes (.getBytes (json/generate-string (first four-split) "UTF-8"))
        four-count (count four-bytes)
        bytes (.getBytes (json/generate-string blocks) "UTF-8")
        byte-count (count bytes)
        byte-limit 6000] ; 16k is the limit but need to account for HTTP
    (timbre/info "Slack limit?: " four-count byte-count byte-limit)
    (if (> four-count byte-limit)
      (let [parts-num (quot (count blocks)
                            (inc (quot byte-count byte-limit)))
            split-num (if (> default-split parts-num)
                          default-split
                          parts-num)
            parts (partition split-num split-num nil blocks)]
        {:intro (first parts) :rest (rest parts)})
      {:intro (first four-split) :rest (rest four-split)})))

(defn- posts-with-board-name [board]
  (let [board-name (:name board)]
    (assoc board :posts (map #(assoc % :board-name board-name) (:posts board)))))

(defn send-digest [token {channel :id :as receiver} {:keys [org-name org-slug logo-url boards] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [intro (str ":coffee: Good morning " (or org-name "Carrot"))
        banner-block {:type "image"
                      :image_url (image/slack-banner-url org-slug logo-url)
                      :alt_text org-name
                      :title {
                        :type "plain_text"
                        :text org-name}}
        footer-selection (inc (rand-int 6)) ; 1 through 6
        footer-block {:type "image"
                      :image_url (image/slack-footer-url footer-selection)
                      :alt_text "The end"
                      :title {
                       :type "plain_text"
                       :text "The end"}}

        boards (map posts-with-board-name (:boards msg))
        all-posts (mapcat :posts boards)
        sorted-posts (sort sort-must-see-board-name all-posts)
        must-see (filter :must-see sorted-posts)
        non-must-see (filter (comp not :must-see) sorted-posts)
        must-see-blocks (flatten (map #(post-as-block % msg) must-see))
        regular-blocks (flatten (map #(post-as-block % msg) non-must-see))
        ;; Remove the last divider
        all-posts-blocks (butlast (concat must-see-blocks regular-blocks))
        all-blocks (concat [banner-block]
                            all-posts-blocks
                            [footer-block])
        split-blocks (split-blocks all-blocks)]
    (timbre/debug "Footer attachment:" footer-block)
    (timbre/info "Sending digest to:" channel " with:" token)
    (if (:intro split-blocks)
      (do
        (slack/post-blocks token
                           channel
                           (:intro split-blocks)
                           intro)
        (doseq [part (:rest split-blocks)]
          (slack/post-blocks token channel part)))
      (slack/post-blocks token channel (:rest split-blocks) intro))))