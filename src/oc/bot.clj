(ns oc.bot
  (:require [com.stuartsierra.component :as component]
            [amazonica.aws.sqs :as aws-sqs]
            [clojure.java.io :as io]
            [environ.core :as e]
            [manifold.stream :as s]
            [taoensso.timbre :as timbre]
            [oc.bot.sqs :as sqs]
            [oc.bot.slack :as slack]
            [oc.bot.conversation :as conv]
            [oc.bot.message :as msg]))

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
                       (slack/initialize-connection! (:slack sys) bot-token))]
    ;; (prn 'slack-conn slack-conn)
    (timbre/infof "Received message from SQS: %s\n" msg-body)
    (s/put! (:in-proxy slack-conn) (assoc msg-body :type ::initialize)))
    msg)

(comment
  (do 
    (def bot-user-id "U10AR0H50")
    (def bot-testing-ch "C10A1P4H2")
    (def dm-testing-ch "D108XUAFM")

    (def test-msg {:type "message" :channel bot-testing-ch :text "Slack connection pooling works!"
                   :bot-token (e/env :slack-bot-token) :bot-user-id bot-user-id})

    (defn test-onboard-trigger []
      {:diff     (rand-int 1000)
       :script   {:id :onboard :params {:name "Sean" :company-name "Flickr" :company-slug "flickr" :company-dashboard "https://opencompany.com/flickr" :company-description "The home for all your photos." :contact-person "Tom" :currency "$"}}
       :receiver {:type :channel :id dm-testing-ch}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    (defn test-su-trigger []
      {:diff     (rand-int 1000)
       :script   {:id :stakeholder-update :params {:name "Sarah" :company-name "Flickr" :stakeholder-update-link "https://opencompany.com/flickr"}}
       :receiver {:type :channel :id dm-testing-ch}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    (defn test-onboard-user-trigger []
      {:diff     (rand-int 1000)
       :script   {:id :onboard-user :params {:name "Sarah" :company-name "Flickr" :company-dashboard "https://opencompany.com/flickr" :contact-person "@stuart"}}
       :receiver {:type :channel :id dm-testing-ch}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    (defn test-onboard-user-authenticated-trigger []
      {:diff     (rand-int 1000)
       :script   {:id :onboard-user :params {:name "Sarah" :company-name "Flickr" :company-dashboard "https://opencompany.com/flickr"}}
       :receiver {:type :channel :id dm-testing-ch}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    
    )

  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) (test-onboard-trigger))

  (def sys (system {:sqs-queue (e/env :aws-sqs-queue)
                    :sqs-msg-handler sqs-handler}))

  (alter-var-root #'sys component/start)

  (alter-var-root #'sys component/stop)

  )