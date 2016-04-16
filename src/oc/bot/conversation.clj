(ns oc.bot.conversation
  (:require [com.stuartsierra.component :as component]
            [manifold.deferred :as d]
            [manifold.stream :as s]))

(defrecord Conversation [in out init-state transition-fn]
  component/Lifecycle
  (start [component]
    (let [id    (java.util.UUID/randomUUID)
          state (atom init-state)]
      (s/on-closed in #(prn 'closed id))
      (s/consume #(swap! state transition-fn out %) in)
      (assoc component :state state)))
  (stop [component]
    (dissoc component :state)))

(defrecord ConversationManager [in out dispatcher]
  component/Lifecycle
  (start [component]
    (println ";; Starting Conversation Manager")
    (let [conversations (atom {})
          started       (assoc component :conversations conversations)]
      (s/on-closed in #(prn 'closed-conv-mngr-in))
      (s/consume #(dispatcher started %) in)
      started))
  (stop [component]
    (println ";; Stopping Conversation Manager")
    (dissoc component :conversations)))


;; basic conv:
;; receive message
;; reply with 'hello' + msg
;; store list of received messages in state

(defn test-transition-fn [state out-stream msg]
  (let [new-state (conj state (:body msg))]
    (s/put! out-stream (str "hello " (clojure.string/join ", " new-state)))
    new-state))

(defn mk-conv [out]
  (map->Conversation {:in  (s/stream)
                      :out out
                      :init-state []
                      :transition-fn test-transition-fn}))

(defn msg->predicate [base-msg]
  (fn [msg]
    (= (:channel msg) (:channel base-msg))))

;; TODO add tests
(defn predicate-map-lookup [pred-map v]
  (reduce (fn [acc pred]
            (if (pred v)
              (conj acc (get pred-map pred))
              acc))
          (list)
          (keys pred-map)))

(defn find-matching-conv [convs msg]
  (let [[f s] (predicate-map-lookup convs msg)]
    (when s
      (println "predicate-map-lookup returned multiple results, using first"))
    f))

(defn dispatch! [conv-mngr incoming-msg]
  ;; (prn conv-mngr)
  (prn {:no-of-convs (count @(:conversations conv-mngr))})
  (if-let [conv (find-matching-conv @(:conversations conv-mngr) incoming-msg)]
    (s/put! (:in conv) incoming-msg)
    (let [new-conv (component/start (mk-conv (:out conv-mngr)))]
      (swap! (:conversations conv-mngr)
             assoc
             (msg->predicate incoming-msg)
             new-conv)
      (s/put! (:in new-conv) incoming-msg))))

(comment
  (def in (s/stream))

  (def out (s/stream))

  (s/consume prn out)

  (def conv-mngr
   (map->ConversationManager {:in in :out out
                              :dispatcher dispatch!}))

  (alter-var-root #'conv-mngr component/start)

  (alter-var-root #'conv-mngr component/stop)

  (s/put! in {:channel 2 :body "martin"})

  (let [m {:a 1, :b 2, :c 3}
        v {:a 1 :c 9}]
    (predicate-map-lookup m v)))



;; #oc.bot.conversation.ConversationManager{:in << stream: {:pending-puts
;;  0, :drained? false, :buffer-size 0, :permanent? false, :type
;;  "manifold", :sink? true, :closed? false, :pending-takes 0,
;;  :buffer-capacity 0, :source? true} >>, :out << stream: {:pending-puts
;;  0, :drained? false, :buffer-size 0, :permanent? false, :type
;;  "manifold", :sink? true, :closed? false, :pending-takes 1,
;;  :buffer-capacity 0, :source? true} >>, :dispatcher
;;  #object[oc.bot.conversation$dispatch_BANG_ 0x7893cb22
;;  "oc.bot.conversation$dispatch_BANG_@7893cb22"]}