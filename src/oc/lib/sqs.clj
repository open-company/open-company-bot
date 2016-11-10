(ns oc.lib.sqs
  "
  A component to consume messages from an SQS queue with a long poll and pass them off to a handler, deleting them if
  they are processed successfully (no exception) by the handler.

  https://github.com/stuartsierra/component
  "
  (:require [com.stuartsierra.component :as component]
            [amazonica.aws.sqs :as sqs]
            [manifold.stream :as s]
            [manifold.time :as t]
            [manifold.deferred :as d]
            [taoensso.timbre :as timbre]))

(defn- get-message
  "Get a single message from SQS"
  [sqs-creds sqs-queue-url]
  (-> (sqs/receive-message sqs-creds
                           :queue-url sqs-queue-url
                           :wait-time-seconds 2
                           :max-number-of-messages 1)
      :messages first))

(defn- delete-message!
  "Delete a previously received message so it cannot be retrieved by other consumers"
  [sqs-creds sqs-queue-url msg]
  (timbre/trace "Deleteing message" msg  "in queue" sqs-queue-url)
  (sqs/delete-message sqs-creds (assoc msg :queue-url sqs-queue-url)))

(defn- msg-tracer [m]
  (timbre/trace "Processing message:" m)
  m)

(defn- process
  "
  Yield to a message handling deferred function that will ultimately call the `msg-delete` function if handling
  succeeds.
  
  If the `msg-handler` function throws an exception, `msg-delete` will not be called and an error will be logged.
  "
  [msg-handler msg-delete]
  (let [res (d/deferred)]
    (-> res
        (d/chain msg-tracer msg-handler msg-delete)
        (d/catch #(do (timbre/error "Failed to process SQS message due to an exception.")
                      (timbre/error %))))
    res))

(defn- dispatch-message
  "Check for a message and, if one is available, put it into the given deferrred"
  [sqs-creds sqs-queue-url deferred]
  (timbre/trace "Checking for messages in queue:" sqs-queue-url)
  (try
    (when-let [m (get-message sqs-creds sqs-queue-url)]
      (timbre/info "Got message from queue:" sqs-queue-url)
      (d/success! deferred m))
    (catch Throwable e
      (timbre/error "Exception while polling SQS:" e)
      (throw e))))

(defrecord SQSListener [sqs-creds sqs-queue-url message-handler]
  
  ;; Implement the Lifecycle protocol
  component/Lifecycle
  
  (start [component]
    (timbre/info "Starting SQSListener")
    (let [handle! (partial message-handler component)
          delete! (partial delete-message! sqs-creds sqs-queue-url)
          processor (fn [] (process handle! delete!))
          retriever (t/every 3000 #(dispatch-message sqs-creds sqs-queue-url (processor)))]
      (assoc component :retriever retriever)))

  (stop [component]
    (timbre/info "Stopping SQSListener")
    (when-let [r (:retriever component)] (r))
    (dissoc component :retriever)))

(defn sqs-listener [sqs-creds sqs-queue-url message-handler]
  (map->SQSListener {:sqs-creds sqs-creds :sqs-queue-url sqs-queue-url :message-handler message-handler}))