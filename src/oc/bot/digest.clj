(ns oc.bot.digest
  "
  Namespace to convert an OC digest request into a Slack message with an attachment per post, and to send it via Slack.
  "
  (:require [taoensso.timbre :as timbre]
            [jsoup.soup :as soup]
            [oc.lib.text :as text]
            [oc.lib.html :as html]
            [oc.lib.slack :as slack]
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

(defn- board-access-string [board-access]
  (cond
    (= board-access "private")
    " (private)"
    (= board-access "public")
    " (public)"
    :else
    ""))

(defn- post-as-chunk [{:keys [publisher url headline abstract published-at comment-count-label new-comment-label
                              board-name board-access interaction-attribution must-see video-id body uuid
                              reactions]} msg]
  (let [author-name (:name publisher)
        clean-headline (post-headline headline)
        reduced-body (if (s/blank? abstract) (text/truncated-body body) abstract)
        accessory-image (:thumbnail (html/first-body-thumbnail body))
        has-accessory-image? (not-empty accessory-image)
        seen-attach [{:type "button"
                      :text "👀 View post"
                      :url url}]
        message {:fallback (str "A post in " board-name " by " author-name ", '" clean-headline "'.")
                 :color (if must-see "#6187f8" "#e8e8e8")
                 :author_name (str author-name " in " board-name)
                 :author_icon (user-avatar/fix-avatar-url config/filestack-api-key (:avatar-url publisher))
                 :title clean-headline
                 :title_link url
                 :text reduced-body
                 :actions seen-attach}
        with-accessory-image (if has-accessory-image?
                               (assoc message :image_url accessory-image)
                               message)
        comment-label (str comment-count-label
                           (when (seq new-comment-label)
                             (str " (" new-comment-label ")")))]
    (if (seq comment-count-label)
      (assoc with-accessory-image :footer comment-label)
      with-accessory-image)))

(defn- posts-with-board-name [board]
  (let [board-name (:name board)]
    (assoc board :posts (map #(assoc % :board-name board-name) (:posts board)))))

(defn send-digest [token {channel :id :as receiver} {:keys [org-name org-slug logo-url boards] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [intro           (str ":coffee: Good morning " (or org-name "Carrot"))
        boards          (map posts-with-board-name (:boards msg))
        all-posts       (mapcat :posts boards)
        sorted-posts    (sort-by (juxt :must-see :board-name :published-at) all-posts)
        all-chunks      (mapv #(post-as-chunk % msg) sorted-posts)]
    (timbre/debug "Chunk count:" (count all-chunks))
    (timbre/info "Sending digest to:" channel " with:" token)
    (slack/post-attachments token channel [(first all-chunks)] intro)
    (doseq [msg (drop 1 all-chunks)]
      (slack/post-attachments token channel [msg]))))
