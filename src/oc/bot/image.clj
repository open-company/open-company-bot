(ns oc.bot.image
  (:require [clj-http.client :as http]
            [clojure.java.io :as io]
            [amazonica.aws.s3 :as s3]
            [taoensso.timbre :as timbre]
            [oc.bot.config :as c])
  (:import  [java.io File]
            [java.net URL]
            [java.awt Font Color]
            [java.awt.image BufferedImage]
            [javax.imageio ImageIO]))


(defn tmp-file
  [org-slug]
  (str "/tmp/" org-slug ".png"))

(defn s3-url
  [org-slug]
  (str "https://" c/slack-digest-s3-bucket ".s3.amazonaws.com/" org-slug ".png"))

(defn exists-on-s3?
  [org-slug]
  (try
    (let [response (http/get (s3-url org-slug) {})
          status (:status response)]
      (= status 200))
    (catch Exception e
      false)))

(defn write-to-s3
  [org-slug]
  ;; put object with client side encryption
  (s3/put-object
    {:access-key c/aws-access-key-id
     :secret-key c/aws-secret-access-key}
    :bucket-name c/slack-digest-s3-bucket
    :key (str org-slug ".png")
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
        font (Font. "Helvitica" Font/BOLD font-size)
        font-x (- (* (count banner-text) font-size) 900)]
    ;; x and y are gathered from the background image.
    ;; scale logo to 212x212
    (.drawImage g logo 896 164 212 212 nil)
    (.setColor g (Color/decode "34414F"))
    (.setFont g font)
    (.drawString g banner-text font-x 625)
    (ImageIO/write (cast BufferedImage source) "png" (File. (tmp-file org-slug)))
    (timbre/debug font-x)
    (timbre/debug (Color/decode "34414F"))
    ;; upload image to s3
    (write-to-s3 org-slug)))

(defn slack-banner-url
  [org-slug logo banner-text]
  ;; test if slack banner is on s3
;;  (if (exists-on-s3? org-slug)
;;    (s3-url org-slug)
;;    (do
  (generate-slack-banner org-slug logo banner-text)
  (s3-url org-slug))
