(ns oc.bot.sqs
  (:require [amazonica.aws.sqs :as sqs]
            [com.stuartsierra.component :as component]
            [manifold.stream :as s]
            [manifold.time :as t]
            [manifold.deferred :as d]
            [environ.core :as e]))

(def creds
  {:access-key (e/env :aws-access-key)
   :secret-key (e/env :aws-secret-key)})

(defn get-message
  "Get a single message from SQS"
  [queue-url]
  (-> (sqs/receive-message creds
                           :queue-url queue-url
                           :wait-time-seconds 2
                           :max-number-of-messages 1)
      :messages first))

(defn delete-message!
  "Delete a previously received message so it cannot be retrieved by other consumers"
  [creds queue-url msg]
  ;; (prn 'deleting-msg (:body msg))
  (sqs/delete-message creds (assoc msg :queue-url queue-url)))

;; (defn relay-message-to-slack
;;   "Relay a given message to slack, mostly used for testing purposes"
;;   [slack-conn msg]
;;   (slack/send-message! (-> sys :slack) (read-string (:body msg)))
;;   msg)

(defn sqs-process*
  "Yield a deferred that will ultimately delete the message put into it
  from SQS unless it fails while handling the message. Logs if it does."
  [msg-handler msg-delete]
  (let [res (d/deferred)]
    (-> res
        (d/chain msg-handler msg-delete (fn delete-report [x] prn 'msg-delete-result x))
        (d/catch Exception #(println "whoops, that didn't work:" %)))
    res))

(defn dispatch-message
  "Check for a message and, if one is available, put it into the given deferrred"
  [queue-url deferred]
  (when-let [m (get-message queue-url)]
    (d/success! deferred m)))

(defrecord SQSListener [queue-url message-handler]
  ;; Implement the Lifecycle protocol
  component/Lifecycle
  (start [component]
    (println ";; Starting SQSListener")
    (let [delete!   (partial delete-message! creds queue-url)
          handle!   (partial message-handler component)   
          processor (fn [] (sqs-process* handle! delete!))
          retriever (t/every 3000 #(dispatch-message queue-url (processor)))]
      ;; (s/consume (partial (:message-handler component) conn (swap! msg-idx inc)) conn)
      ;; (d/catch conn #(str "something unexpected: " (.getMessage %))) ; not sure if this actually does anything 
      (assoc component :retriever retriever)))

  (stop [component]
    (println ";; Stopping SQSListener")
    (when-let [r (:retriever component)] (r))
    (dissoc component :retriever)))

(defn sqs-listener [queue-url message-handler]
  (map->SQSListener {:queue-url queue-url
                     :message-handler (or message-handler #(prn 'sqs-default-handler %))}))

(comment
  (def sqs (map->SQSListener {:queue-url "https://sqs.us-east-1.amazonaws.com/892554801312/my-queue"}))
  
  (alter-var-root #'sqs component/start)

  (alter-var-root #'sqs component/stop)

  (sqs/create-queue creds
                    :queue-name "my-queue"
                    :attributes
                    {:VisibilityTimeout 30 ; sec
                     :MaximumMessageSize 65536 ; bytes
                     :MessageRetentionPeriod 1209600 ; sec
                     :ReceiveMessageWaitTimeSeconds 3}) ; sec

  ;; full list of attributes at
  ;; http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/sqs/model/GetQueueAttributesRequest.html

  (sqs/create-queue creds "DLQ")

  (sqs/list-queues creds)

  (def queue (sqs/find-queue creds "my-queue"))

  (sqs/assign-dead-letter-queue
   creds
   queue
   (sqs/find-queue "DLQ")
   10)

  (def msgs (sqs/receive-message creds queue))

  (time (sqs/receive-message creds queue))


  ;; (sqs/receive-message creds queue)

  (sqs/receive-message creds
                       :queue-url queue
                       :wait-time-seconds 2
                       :max-number-of-messages 1
                       ;; :delete true ;; deletes any received messages after receipt
                       ;; :attribute-names ["All"]
                       )

  (-> "my-queue" sqs/find-queue sqs/delete-queue)
  (->> "DLQ" (sqs/find-queue creds) (sqs/delete-queue creds))

  )