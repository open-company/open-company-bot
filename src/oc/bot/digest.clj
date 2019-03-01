(ns oc.bot.digest
  "
  Namespace to convert an OC digest request into a Slack message with an attachment per post, and to send it via Slack.
  "
  (:require [taoensso.timbre :as timbre]
            [clj-time.coerce :as coerce]
            [cheshire.core :as json]
            [jsoup.soup :as soup]
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

(defn attribution-text
  "
  Given the number of distinct authors to mention, the number of items, what to call the
  item (needs to pluralize with just an 's'), and a sequence of authors of the items
  to attribute (sequence needs to be distinct'able, and have a `:name` property per author),
  return a text string that attributes the authors to the items.
  E.g.
  (attribution 3 7 'comment' [{:name 'Joe'} {:name 'Joe'} {:name 'Moe'} {:name 'Flo'} {:name 'Flo'} {:name 'Sue'}])
  '7 comments by Joe, Moe, Flo and others'
  "
  [attribution-count item-count item-name authors]
  (let [distinct-authors (distinct authors)
        author-names (map :name (take attribution-count distinct-authors))
        more-authors? (> (count distinct-authors) (count author-names))
        multiple-authors? (> (count author-names) 1)
        author-attribution (cond
                              ;; more distinct authors than we are going to mention individually
                              more-authors?
                              (let [other-count (- (count distinct-authors)
                                                   attribution-count)]
                                (str (clojure.string/join ", " author-names)
                                     " and "
                                     other-count
                                     " other"
                                     (when (> other-count 1)
                                       "s")))

                              ;; more than 1 author so last mention needs an "and", not a comma
                              multiple-authors?
                              (str (clojure.string/join ", " (butlast author-names))
                                                        " and "
                                                        (last author-names))

                              ;; just 1 author
                              :else
                              (first author-names))]
    (timbre/info "\n\nA:" (vec authors))
    (timbre/info "DA:" (vec distinct-authors))
    (timbre/info "AN:" (vec author-names))
    (timbre/info "MA:" multiple-authors?)
    (timbre/info "AA:" author-attribution)
    (timbre/info (str item-count " " item-name (when (> item-count 1) "s") " by " author-attribution) "\n\n")
    (str item-count " " item-name (when (> item-count 1) "s") " by " author-attribution)))

(defn- attribution [comment-authors comment-count reaction-data receiver]
  (timbre/info "\n\nCA:" comment-authors)
  (let [comments (text/attribution 3 comment-count "comment" comment-authors)
        reaction-authors (map #(hash-map :name %)
                              (flatten (map :authors reaction-data)))
        reaction-author-ids (or (flatten (map :author-ids reaction-data)) [])
        reaction-authors-you (if (some #(= % (:user-id receiver))
                                       reaction-author-ids)
                               (map #(if (= (:name receiver) (:name %))
                                       (assoc % :name "you")
                                       %)
                                    reaction-authors)
                               reaction-authors)
        comment-authors-you (map #(when (= (:user-id receiver) (:user-id %))
                                    (assoc % :name "you"))
                                 comment-authors)
        comment-authors-name (map #(hash-map :name (:name %))
                                  comment-authors-you)
        total-authors (vec (set
                            (concat reaction-authors-you
                                    comment-authors-name)))
        total-authors-sorted (remove #(nil? (:name %))
                               (conj (remove #(= (:name %) "you")
                                             total-authors)
                                     (first (filter #(= (:name %) "you")
                                                    total-authors))))
        reactions (text/attribution 3
                                    (count reaction-data)
                                    "reaction"
                                    reaction-authors)
        total-attribution (attribution-text 2
                                            (+ (count reaction-data)
                                               comment-count)
                                            "comments/reactions"
                                            total-authors-sorted)
        comment-text (clojure.string/join " "
                      (take 2 (clojure.string/split comments #" ")))
        reaction-text (clojure.string/join " "
                       (take 2 (clojure.string/split reactions #" ")))
        author-text (clojure.string/join " "
                      (subvec
                       (clojure.string/split total-attribution #" ") 2))]
    (cond 
      ;; Comments and reactions
      (and (pos? comment-count) (pos? (or (count reaction-data) 0)))
      (str comment-text " and " reaction-text " " author-text)
      ;; Comments only
      (pos? comment-count)
      (str comment-text " " author-text)
      ;; Reactions only
      :else
      (str reaction-text " " author-text))))


(def seen-text "✓ You've viewed this post.")

(defn- post-as-attachment [daily board-name {:keys [publisher url headline published-at comment-count comment-authors must-see video-id body uuid reactions]} msg]
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
          :author_name (str author-name " in " board-name)
          :author_icon (:avatar-url publisher)
          :title clean-headline
          :title_link url
          :text reduced-body
          :footer (when seen-this seen-text)
          :actions seen-attach}
          timestamp-map)]
    (if (or (pos? (or comment-count 0))
            (pos? (or (count reactions) 0)))
      (let [interaction-attribution (str ">"
                                      (attribution
                                         comment-authors
                                         comment-count
                                         reactions
                                         {:user-id (:user-id msg)
                                          :name (str (:first-name msg)
                                                     " "
                                                     (:last-name msg))}))
            footer-text (if seen-this (str interaction-attribution "\n" seen-text) interaction-attribution)]
        (assoc message :footer footer-text))
      message)))

(defn- posts-for-board [daily board msg]
  (let [pretext (clojure.string/trim (:name board))
        attachments (map #(post-as-attachment daily (:name board) % msg) (:posts board))]
    (concat [(assoc (first attachments) :pretext (str "*" pretext "*"))] (rest attachments))))

(defn- split-attachments
  "Split message attachments into multiple message if over 16kb
   https://api.slack.com/docs/rate-limits"
  [attachments]
  (let [default-split 5 ;; 4 posts plus header
        four-split (partition default-split default-split nil attachments)
        four-bytes (.getBytes (json/generate-string (first four-split) "UTF-8"))
        four-count (count four-bytes)
        bytes (.getBytes (json/generate-string attachments) "UTF-8")
        byte-count (count bytes)
        byte-limit 6000] ;; 16k is the limit but need to account for HTTP
    (timbre/info "Slack limit?: " four-count byte-count byte-limit)
    (if (> four-count byte-limit)
      (let [parts-num (quot (count attachments)
                            (inc (quot byte-count byte-limit)))
            split-num (if (> default-split parts-num)
                          default-split
                          parts-num)
            parts (partition split-num split-num nil attachments)]
        {:intro (first parts) :rest (rest parts)})
      {:intro (first four-split) :rest (rest four-split)})))

(defn send-digest [token {channel :id :as receiver} {:keys [org-name org-slug logo-url boards] :as msg}]
  {:pre [(string? token)
         (map? receiver)
         (map? msg)]}
  (let [intro (str ":coffee: Good morning " (or org-name "Carrot"))
        intro-attachment {:image_url (image/slack-banner-url org-slug logo-url)
                          :text org-name
                          :fallback "Your morning digest"
                          :color "#FA6452"}
        attachments (conj (flatten (map #(posts-for-board true % msg) boards)) intro-attachment)
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