(ns oc.bot
  (:require [com.stuartsierra.component :as component]
            [amazonica.aws.sqs :as aws-sqs]
            [clojure.java.io :as io]
            [environ.core :as e]
            [oc.bot.slack :as slack]
            [oc.bot.sqs :as sqs]))

(def scripts (read-string (slurp (io/resource "scripts.edn"))))

(defn system [config-options]
  (let [{:keys [sqs-queue sqs-msg-handler]} config-options]
    (component/system-map
      :slack (slack/slack-connection-manager)
      :sqs   (-> (sqs/sqs-listener sqs-queue sqs-msg-handler)
                 (component/using [:slack])))))

(defn sqs-handler [sys msg]
  (let [{:keys [bot-token] :as msg-body} (read-string (:body msg))
        slack-conn (or (slack/connection-for (:slack sys) bot-token)
                       (slack/initialize-connection! (:slack sys) bot-token (fn [conn msg-idx msg] (prn msg))))]
    (prn 'slack-conn slack-conn)
    (slack/send-message! slack-conn msg-body))
    msg)

(comment
  (def bot-testing-ch "C10A1P4H2")
  (def test-msg {:type "message" :channel bot-testing-ch :text "Slack connection pooling works!" :bot-token (e/env :slack-bot-token)})

  (aws-sqs/send-message sqs/creds (e/env :aws-sqs-queue) test-msg)

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