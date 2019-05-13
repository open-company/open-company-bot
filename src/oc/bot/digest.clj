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
            [oc.lib.user-avatar :as user-avatar]
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

(defn- post-as-chunk [{:keys [publisher url headline published-at comment-count comment-authors board-name
                              interaction-attribution must-see video-id body uuid reactions]} msg]
  (let [seen-data (get-seen-data msg uuid)
        author-name (:name publisher)
        clean-headline (post-headline headline)
        reduced-body (text/truncated-body body)
        accessory-image (:thumbnail (html/first-body-thumbnail body))
        has-accessory-image? (not-empty accessory-image)
        ;; if read/seen use seen attachment, else use button
        seen-this? (some #(= (:user-id msg) (:user-id %))
                      (get-in seen-data [:post :read]))
        pre-block {:type "context"
                   :elements (remove nil? [
                    {:type "image"
                     :image_url (user-avatar/fix-avatar-url c/filestack-api-key (:avatar-url publisher))
                     :alt_text author-name}
                    {:type "plain_text"
                     :emoji true
                     :text (str author-name " in " board-name)}
                    (when must-see
                      {:type "image"
                       :image_url (static-image-url "digest_must_see_icon@4x.png")
                       :alt_text "Must See"})
                    (when must-see
                      {:type "plain_text"
                       :emoji true
                       :text "Must See"})])}
        body-block {:type "section"
                    :text {
                      :type "mrkdwn"
                      :text (markdown-post url clean-headline reduced-body)}}
        body-with-thumbnail (if has-accessory-image?
                             (merge body-block
                              {:accessory {
                                :type "image"
                                :image_url accessory-image
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
                       :emoji true
                       :text seen-text}]})
        separator-block {:type "divider"}]
    (remove nil?
     [pre-block
      body-with-thumbnail
      interaction-block
      post-block
      separator-block])))

(defn- build-slack-digest-messages
  "
  Given a banner-block, a footer-block, and a sequence of post-chunks, returns a sequence of messages
  where each message is ready to pass to the slack/post-blocks function.
  Here, chunk is defined as a sequence of blocks. While a post-chunk is considered one logical unit, it
  is composed of multiple blocks in order to achieve stylized display in Slack.
  Parition rules are as follows:
  - If there's just 1 post:  all in 1 message
  - If there's just 2 posts: banner and 1st post as 1 message, 2nd post and footer as 1 message
  - if there's 3 posts:      banner and 1st post as 1 message, 2nd post as a message, 3rd post and footer as 1 message
  - if there's 4+ posts:     banner and 1st post as 1 message, each middle post as a message, last post and footer as 1 message
  With these partition rules the badge count that is displayed in Slack will match the post count
  in Carrot (i.e. banner and footer blocks have no effect on badge count).
  "
  [banner-block post-chunks footer-block]
  (let [num-post-chunks              (count post-chunks)
        middle-post-chunks           (->> post-chunks (drop 1) (butlast))
        header-post-chunk            (into [banner-block] (first post-chunks))
        footer-post-chunk            (conj (vec (last post-chunks)) footer-block)]
    (if (>= num-post-chunks 2)
      (concat [header-post-chunk]
              middle-post-chunks
              [footer-post-chunk])
      (concat [banner-block] (flatten post-chunks) [footer-block])
      )))

(defn- posts-with-board-name [board]
  (let [board-name (:name board)]
    (assoc board :posts (map #(assoc % :board-name board-name) (:posts board)))))

(defn send-digest [token {channel :id :as receiver} {:keys [org-name org-slug logo-url boards] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [intro            (str ":coffee: Good morning " (or org-name "Carrot"))
        banner-block     {:type      "image"
                          :image_url (image/slack-banner-url org-slug logo-url)
                          :alt_text  org-name
                          :title     {
                                      :type  "plain_text"
                                      :emoji true
                                      :text  org-name}}
        footer-selection (inc (rand-int 6)) ; 1 through 6
        footer-block     {:type      "image"
                          :image_url (image/slack-footer-url footer-selection)
                          :alt_text  "The end"
                          :title     {
                                      :type "plain_text"
                                      :text "The end"}}

        boards          (map posts-with-board-name (:boards msg))
        all-posts       (mapcat :posts boards)
        sorted-posts    (sort-by (juxt :must-see :board-name :published-at) all-posts)
        must-see        (filter :must-see sorted-posts)
        non-must-see    (filter (comp not :must-see) sorted-posts)
        must-see-chunks (mapv #(post-as-chunk % msg) must-see)
        regular-chunks  (mapv #(post-as-chunk % msg) non-must-see)
        regular-chunks* (update regular-chunks (-> regular-chunks count dec) butlast) ;; remove the last divider
        all-chunks      (concat must-see-chunks regular-chunks*)
        digest-messages (build-slack-digest-messages banner-block
                                                     all-chunks
                                                     footer-block)
        ]
    (timbre/debug "Banner attachment:" banner-block)
    (timbre/debug "Chunk count:" (count all-chunks))
    (timbre/debug "Footer attachment:" footer-block)
    (timbre/info "Sending digest to:" channel " with:" token)
    (doseq [msg digest-messages]
      (slack/post-blocks token channel msg intro))))
