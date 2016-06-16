(ns oc.bot.language
  (:require [clojure.string :as string]
            [aleph.http :as http]
            [manifold.deferred :as d])
  (:import [org.apache.commons.validator UrlValidator]))

(defn image? [s]
  (string/starts-with? s "image"))

(defn extract-url [url-str]
  (let [s (second (first (re-seq #"<(.*)>" url-str)))
        validator (UrlValidator.)]
    (when (.isValid validator s) s)))

;; ==================================================================

(def yes? #{"y" "yes"})

(def no? #{"n" "no" "not yet"})

(def euro? #{"€" "eur" "euro"})

(def dollar? #{"$" "usd" "dollar"})

(defn downloadable-image? [s]
  (when-let [url (extract-url s)]
    @(-> (http/head url)
         (d/chain #(when (and (= 200 (:status %))
                              (-> % :headers (get "content-type") image?))
                     url))
         (d/catch (fn [_] false))))) ; aleph sets "content-type" https://github.com/ztellman/aleph/blob/1427d8142b1762244645425dc6bee685feb15a95/src/aleph/http/client_middleware.clj#L282-L289

(comment
  (downloadable-image? "https://-R6qwnGcrHlY/AAAAAAAAAAI/AAAAAAAAAAA/SuoCUhq2DAM/w80-h80/photo.jpg")
  (valid-url "https://-R6qwnGcrHlY/AAAAAAAAAAI/AAAAAAAAAAA/SuoCUhq2DAM/w80-h80/photo.jpg")

  (valid-url "<https://threaded.martinklepsch.org/fav/apple-icon-152x152.png>")

  (re-seq #"<(.*)>" "<https://threaded.martinklepsch.org/fav/apple-icon-152x152.png>")

  )
