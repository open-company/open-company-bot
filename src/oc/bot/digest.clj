(ns oc.bot.digest
  "
  Namespace to convert an OC digest request into a Slack message with an attachment per post,
  and to send it via Slack.
  "
  (:require [taoensso.timbre :as timbre]
            [clj-time.coerce :as coerce]
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

(defn- attribution [comment-authors comment-count reaction-data receiver]
  (let [comments (text/attribution 3 comment-count "comment" comment-authors)
        reaction-authors (map #(hash-map :name %)
                              (flatten (map :authors reaction-data)))
        reaction-author-ids (or (flatten (map :author-ids reaction-data)) [])
        reaction-authors-you (if (some #(= % (:user-id receiver))
                                       reaction-author-ids)
                               (map #(when (= (:name receiver) (:name %))
                                       (assoc % :name "You"))
                                    reaction-authors)
                               reaction-authors)
        comment-authors-you (map #(when (= (:user-id receiver) (:user-id %))
                                    (assoc % :name "You"))
                                 comment-authors)
        comment-authors-name (map #(hash-map :name (:name %))
                                  comment-authors-you)
        total-authors (vec (set
                            (concat reaction-authors-you
                                    comment-authors-name)))
        reactions (text/attribution 3
                                    (count reaction-data)
                                    "reaction"
                                    reaction-authors)
        total-attribution (text/attribution 3
                                            (+ (count reaction-data)
                                               comment-count)
                                            "comments/reactions"
                                            total-authors)
        comment-text (clojure.string/join " "
                      (take 2 (clojure.string/split comments #" ")))
        reaction-text (clojure.string/join " "
                       (take 2 (clojure.string/split reactions #" ")))
        author-text (clojure.string/join " "
                      (subvec
                       (clojure.string/split total-attribution #" ") 2))]
    (str comment-text " and " reaction-text " " author-text)))


(defn- post-as-attachment [daily board-name {:keys [publisher url headline published-at comment-count comment-authors must-see video-id body uuid reactions]} msg]
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
      (assoc message :footer (attribution
                               comment-authors
                               comment-count
                               reactions
                               {:user-id (:user-id msg)
                                :name (str (:first-name msg)
                                           " "
                                           (:last-name msg))}))
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