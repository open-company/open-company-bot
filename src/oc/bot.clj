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

    (defn test-onboard-trigger [ch-id]
      {:diff     (rand-int 1000)
       :script   {:id :onboard :params {:user/name "Sarah" :company/name "Flickr" :company/slug "flickr"
                                        :company/description "The home for all your photos." :company/currency "$"
                                        :contact-person "Tom"}}
       :receiver {:type :channel :id ch-id}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    (defn test-su-trigger [ch-id]
      {:diff     (rand-int 1000)
       :script   {:id :stakeholder-update :params {:user/name "Sarah" :company/name "Flickr"}}
       :receiver {:type :channel :id ch-id}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    (defn test-onboard-user-trigger [ch-id]
      {:diff     (rand-int 1000)
       :script   {:id :onboard-user :params {:name "Sarah" :company/name "Flickr" :company/dashboard "https://opencompany.com/flickr" :contact-person "@stuart"}}
       :receiver {:type :channel :id ch-id}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    (defn test-onboard-user-authenticated-trigger [ch-id]
      {:diff     (rand-int 1000)
       :script   {:id :onboard-user :params {:name "Sarah" :company/name "Flickr" :company/dashboard "https://opencompany.com/flickr"}}
       :receiver {:type :channel :id ch-id}
       :bot      {:token (e/env :slack-bot-token) :id bot-user-id}})
    
    )

  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) (test-onboard-trigger bot-testing-ch))
  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) (test-onboard-trigger dm-testing-ch))

  (def sys (system {:sqs-queue (e/env :aws-sqs-queue)
                    :sqs-msg-handler sqs-handler}))

  (alter-var-root #'sys component/start)

  (alter-var-root #'sys component/stop)

  ;; Messages after bot got invited into channel
  {:type "channel_joined", :channel {:creator "U06SBTXJR", :purpose {:value "Discuss development of the OPENcompany platform", :creator "U06SBTXJR", :last_set 1454426238}, :is_channel true, :name "development", :is_member true, :is_archived false, :created 1448888630, :topic {:value "", :creator "", :last_set 0}, :latest {:type "message", :user "U0JSATHT3", :text "<@U06SBTXJR>: can you kick the bot out of <#C10A1P4H2> please?", :ts "1461337454.001157"}, :id "C0FGNSA2V", :unread_count_display 0, :last_read "1461337454.001157", :members ["U06SBTXJR" "U06SQLDFT" "U06STCKLN" "U0J5LK571" "U0JSATHT3" "U10AR0H50"], :is_general false, :unread_count 0}}
  {:user "U10AR0H50", :inviter "U0JSATHT3", :type "message", :subtype "channel_join", :team "T06SBMH60", :text "<@U10AR0H50|transparency> has joined the channel", :channel "C0FGNSA2V", :ts "1461337622.001158"}
  ;; Events after being kicked
  {:type "channel_left", :channel "C10A1P4H2"}
  )