(ns oc.bot.digest
  "
  Namespace to convert an OC digest request into a Slack message with an attachment per post, and to send it via Slack.
  "
  (:require [taoensso.timbre :as timbre]
            [jsoup.soup :as soup]
            [oc.lib.text :as text]
            [oc.lib.html :as html]
            [oc.lib.slack :as slack]
            [oc.lib.user :as user-avatar]
            [oc.bot.config :as c]
            [clojure.string :as s]
            [clj-time.core :as t]
            [clj-time.format :as f]))

(def date-format (f/formatter "MMMM d, YYYY"))

(defonce footer-fallbacks [
  "That's all for now!"
  "You're all caught up."
  "Have a great day!"
  "Now you're in sync."
  "Go seize the day."
  "That's a wrap, enjoy the day!"])

(defn post-headline [headline]
  (.text (soup/parse headline)))

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
                              reactions follow-up]} msg]
  (let [author-name (:name publisher)
        clean-headline (post-headline headline)
        headline-with-tag (cond
                            follow-up (str clean-headline " [Follow-up]")
                            must-see (str clean-headline " [Must See]")
                            :else clean-headline)
        reduced-body (if (s/blank? abstract) (text/truncated-body body) abstract)
        accessory-image (:thumbnail (html/first-body-thumbnail body))
        has-accessory-image? (not-empty accessory-image)
        btn-attach [{:type "button"
                      :text "👀 View post"
                      :url url}]
        message {:fallback (str "A post in " board-name (board-access-string board-access) " by " author-name ", '" clean-headline "'.")
                 :color (if (or must-see follow-up) "#6187f8" "#e8e8e8")
                 :author_name (str author-name " in " board-name (board-access-string board-access))
                 :author_icon (user-avatar/fix-avatar-url c/filestack-api-key (:avatar-url publisher))
                 :title headline-with-tag
                 :title_link url
                 :text reduced-body
                 :actions btn-attach}
        with-accessory-image (if has-accessory-image?
                               (assoc message :thumb_url accessory-image)
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

(defn get-post-chunks [msg]
  (let [boards          (map posts-with-board-name (:boards msg))
        all-posts       (mapcat :posts boards)
        sorted-posts    (sort-by (juxt :follow-up :board-name :published-at) all-posts)]
    (mapv #(post-as-chunk % msg) sorted-posts)))

(defn send-digest [token {channel :id :as receiver} {:keys [org-name org-slug logo-url boards] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [date-str   (f/unparse date-format (t/now))
        intro      (str ":coffee: Your " (or org-name "Carrot") " daily digest for " date-str)
        all-chunks (get-post-chunks msg)]
    (timbre/debug "Chunk count:" (count all-chunks))
    (timbre/info "Sending digest to:" channel " with:" token)
    (slack/post-attachments token channel [(first all-chunks)] intro)
    (doseq [msg (drop 1 all-chunks)]
      (slack/post-attachments token channel [msg]))))