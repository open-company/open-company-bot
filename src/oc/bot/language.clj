(ns oc.bot.language
  (:require [clojure.string :as string]
            [aleph.http :as http]
            [taoensso.timbre :as timbre]
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

(def not-now? #{"not now" "later"})

(def euro? #{"€" "eur" "euro"})

(def dollar? #{"$" "usd" "dollar"})

(defn downloadable-image? [s]
  (when-let [url (extract-url s)]
    @(-> (http/get url)
         (d/chain (fn [res]
                    (when (and (= 200 (:status res))
                               ;; aleph sets "content-type" https://github.com/ztellman/aleph/blob/1427d8142b1762244645425dc6bee685feb15a95/src/aleph/http/client_middleware.clj#L282-L289
                               (-> res :headers (get "content-type") image?))
                      url)))
         (d/catch (fn [e]
                    (timbre/error e "The following URL caused an exception while checking" {:url url})
                    false)))))

(comment
  (downloadable-image? "<https://-R6qwnGcrHlY/AAAAAAAAAAI/AAAAAAAAAAA/SuoCUhq2DAM/w80-h80/photo.jpg>")

  (downloadable-image? "<http://aleph.io/images/aleph.png>")

  (downloadable-image? "<https://logo.clearbit.com/pantheon.io?s=126>")

  (extract-url "https://-R6qwnGcrHlY/AAAAAAAAAAI/AAAAAAAAAAA/SuoCUhq2DAM/w80-h80/photo.jpg")

  (extract-url "<https://threaded.martinklepsch.org/fav/apple-icon-152x152.png>")

  (re-seq #"<(.*)>" "<https://threaded.martinklepsch.org/fav/apple-icon-152x152.png>")

  (extract-url "<http://media.giphy.com/media/xTiTnlgsJTVPK9hGZa/giphy.gif>")

  (downloadable-image? "<http://media.giphy.com/media/xTiTnlgsJTVPK9hGZa/giphy.gif>")

  (downloadable-image? "<https://media.giphy.com/media/xTiTnlgsJTVPK9hGZa/giphy.gif>")

  (downloadable-image? "<https://pbs.twimg.com/profile_images/700577267127193600/PtLt3m6R.png>")
  (downloadable-image? "<http://pbs.twimg.com/profile_images/700577267127193600/PtLt3m6R.png>")

  (downloadable-image? "<https://bigoven-res.cloudinary.com/image/upload/t_recipe-256/mexican-breakfast-burrito-1365962.jpg>")

  ;; https://github.com/ztellman/aleph/issues/253
  @(-> (aleph.http/head "http://aleph.io/images/aleph.png")
       (manifold.deferred/chain (fn [res] (prn res))))

  @(-> (aleph.http/get "http://aleph.io/images/aleph.png")
       (manifold.deferred/chain (fn [res] (prn res))))



  )
