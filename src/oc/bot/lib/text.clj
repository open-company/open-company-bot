(ns oc.bot.lib.text
  (:require [clojure.string :as s]
            [cuerdas.core :as str]
            [jsoup.soup :as soup]))

(defn- clean-text [text]
  (-> text
    (s/replace #"&nbsp;" " ")
    (str/strip-tags)
    (str/strip-newlines)))

(defn clean-html [text]
  (if-not (s/blank? text)
    (clean-text (.text (soup/parse text)))
    ""))