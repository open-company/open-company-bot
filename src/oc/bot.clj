(ns oc.bot
  (:require [com.stuartsierra.component :as component]
            [amazonica.aws.sqs :as aws-sqs]
            [clojure.java.io :as io]
            [environ.core :as e]
            [oc.bot.slack :as slack]
            [oc.bot.message :as msg]
            [oc.bot.sqs :as sqs]))

(defn system [config-options]
  (let [{:keys [sqs-queue sqs-msg-handler]} config-options]
    (component/system-map
      :slack (slack/slack-connection-manager)
      :sqs   (-> (sqs/sqs-listener sqs-queue sqs-msg-handler)
                 (component/using [:slack])))))

(defn slack-handler [conn msg-idx msg] (prn msg))

(defn sqs-handler [sys msg]
  (let [msg-body   (read-string (:body msg))
        bot-token  (-> msg-body :bot :token)
        slack-conn (or (slack/connection-for (:slack sys) bot-token)
                       (slack/initialize-connection! (:slack sys) bot-token slack-handler))]
    ;; (prn 'slack-conn slack-conn)
    (doseq [m (msg/messages-for :onboard (-> msg-body :script :params))]
      (slack/send-event! slack-conn {:type "typing"
                                     :channel (-> msg-body :receiver :id)})
      (slack/send-event! slack-conn {:type "message"
                                     :text m
                                     :channel (-> msg-body :receiver :id)})))
    msg)

(comment
  (def bot-user-id "U10AR0H50")
  (def bot-testing-ch "C10A1P4H2")

  (def test-msg {:type "message" :channel bot-testing-ch :text "Slack connection pooling works!"
                 :bot-token (e/env :slack-bot-token) :bot-user-id bot-user-id})

  (def test-onboard-trigger
    {:script {:id :onboard :params {:name "Sarah" :company "Flickr" :company-dashboard "https://opencompany.com/flickr" :contact-person "Tom"}}
     :receiver {:type :channel :id bot-testing-ch}
     :bot {:token (e/env :slack-bot-token) :id bot-user-id}})

  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) test-onboard-trigger)

  (def sys (system {:sqs-queue (e/env :aws-sqs-queue)
                    :sqs-msg-handler sqs-handler
                    ;; :slack-msg-handler (fn [conn msg-idx msg] (prn msg))
                    ;; :slack-token (e/env :slack-bot-token)
                    }))

  (alter-var-root #'sys component/start)

  (-> sys :slack :connections deref keys)

  (alter-var-root #'sys component/stop)

  ;; Repro for https://github.com/ztellman/manifold/issues/73
  ;; =========================================================
  ;; (require '[manifold.deferred :as d])

  ;; (let [dd (d/deferred)]
  ;;   (-> dd
  ;;       (d/chain #(do (assert (string? %) "invalid") %) prn)
  ;;       (d/catch #(str "something unexpected:" (.getMessage %))))
  ;;   (d/success! dd nil))

  ;; (try
  ;;   (assert nil "stuff breaks")
  ;;   (catch Throwable e (prn 'caught)))

  )