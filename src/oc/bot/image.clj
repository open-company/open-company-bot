(ns oc.bot.image
  (:require [clj-http.client :as http]
            [clojure.java.io :as io]
            [amazonica.aws.s3 :as s3]
            [oc.bot.config :as c])
  (:import  [java.io File]
            [java.net URL]
            [java.awt Font FontMetrics Color]
            [java.awt.image BufferedImage]
            [javax.imageio ImageIO]))


(defn tmp-file
  [org-slug]
  (str "/tmp/" org-slug ".png"))

(defn s3-file-key
  [org-slug text]
  (clojure.string/lower-case
    (str org-slug (clojure.string/replace text #"[\s\W]" "_") ".png")))

(defn s3-url
  [org-slug text]
  (str "https://" c/slack-digest-s3-bucket ".s3.amazonaws.com/" (s3-file-key org-slug text)))

(defn exists-on-s3?
  [org-slug text]
  (try
    (let [response (http/get (s3-url org-slug text) {})
          status (:status response)]
      (= status 200))
    (catch Exception e
      false)))

(defn write-to-s3
  [org-slug text]
  ;; put object with client side encryption
  (s3/put-object
    {:access-key c/aws-access-key-id
     :secret-key c/aws-secret-access-key}
    :bucket-name c/slack-digest-s3-bucket
    :key (s3-file-key org-slug text)
    :file (File. (tmp-file org-slug))
    :access-control-list {
     :grant-all [
      ["AllUsers" "Read"]
      ["AuthenticatedUsers" "Write"]]}))

(defn- org-logo [logo]
  (if logo
    (io/input-stream (URL. logo))
    (File. "src/oc/assets/img/carrot_logo.png")))

(defn generate-slack-banner
  ""
  [org-slug logo banner-text]
  (let [source (ImageIO/read
                (File. "src/oc/assets/img/slack-digest-background.png"))
        logo (ImageIO/read (org-logo logo))
        g (.getGraphics source)
        font-size 48 ;; 12px x 4
        font (Font. "Helvitica" Font/BOLD font-size)]
    ;; x and y are hard coded from the background image.
    ;; scale logo to 212x212
    (.drawImage g logo 896 164 212 212 nil)
    (.setColor g (Color. 52, 65, 79)) ;;rgb of #34414F
    (.setFont g font)
    (let [fontx (quot ;; cal x position for font ((img width - font length) / 2)
                  (-
                    2000
                    (.stringWidth
                     (.getFontMetrics g)
                     banner-text))
                  2)]
      (.drawString g banner-text fontx 625))
    (let [_ (ImageIO/write (cast BufferedImage source) "png" (File. (tmp-file org-slug)))]
      ;; upload image to s3
      (write-to-s3 org-slug banner-text))))

(defn slack-banner-url
  [org-slug logo banner-text]
  ;; test if slack banner is on s3
  (if (exists-on-s3? org-slug banner-text)
    (s3-url org-slug banner-text)
    (do
      (generate-slack-banner org-slug logo banner-text)
      (s3-url org-slug banner-text))))
