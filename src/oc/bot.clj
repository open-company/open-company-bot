(ns oc.bot
  (:require [com.stuartsierra.component :as component]
            [amazonica.aws.sqs :as aws-sqs]
            [clojure.java.io :as io]
            [environ.core :as e]
            [oc.bot.slack :as slack]
            [oc.bot.sqs :as sqs]))

(def scripts (read-string (io/resource "scripts.edn")))

(defn system [config-options]
  (let [{:keys [sqs-queue sqs-msg-handler slack-msg-handler]} config-options]
    (component/system-map
      :slack (slack/slack-connection slack-msg-handler) 
      :sqs   (-> (sqs/sqs-listener sqs-queue sqs-msg-handler)
                 (component/using [:slack])))))

(comment
  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) {:type "message" :channel slack/bot-testing-ch :text "Sent via SQS!"})

  (defn sqs-handler [sys msg]
    (prn 'processing-message)
    (slack/send-message! (-> sys :slack) (read-string (:body msg)))
    msg)

  (def sys (system {:sqs-queue (e/env :aws-sqs-queue)
                    :slack-msg-handler (fn [conn msg-idx msg] (prn msg))
                    :sqs-msg-handler sqs-handler}))

  (alter-var-root #'sys component/start)

  (alter-var-root #'sys component/stop)


  )