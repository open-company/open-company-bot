(ns oc.bot.conversation
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [automat.core :as a]
            [oc.bot.message :as m]
            [oc.bot.utils :as u])
  (:import [java.time LocalDateTime]))

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
      ;; use connect-via here perhaps
      (s/consume #(dispatcher started %) in)
      started))
  (stop [component]
    (println ";; Stopping Conversation Manager")
    (dissoc component :conversations)))

;; -----------------------------------------------------------------------------
;; Conversation state
;; Conversations as state machines. Coerce messages into transitions.
;; Build data to look up messages to be sent.
;; -----------------------------------------------------------------------------

(defn fact-check [update-transition]
  [(a/or
    [:yes (a/$ :confirm)]
    [(a/+ [:no [update-transition (a/$ :update)]])
     [:yes (a/$ :confirm)]])])

(def fact-checker
  (a/compile [:init 
              (fact-check :str) ;name
              [:next-stage (a/$ :next-stage)]
              (fact-check :str) ;desc
              [:next-stage (a/$ :next-stage)]
              (fact-check :currency) ;currency
              [:next-stage (a/$ :next-stage)]
              (fact-check :user) ;ceo
              ]
             {:signal   first
              :reducers {:next-stage (fn [state input] (update state :stage (fn [s] (u/next-in (:stages state) s))))
                         :confirm (fn [state input] (update state :confirmed (fnil conj #{}) (:stage state)))
                         :update (fn [state [sig v]] (assoc-in state [:updated (:stage state)] v))}}))

(def init-only (a/compile [:init] {:signal first}))

;; FSM testing
(comment 
  (def adv (partial a/advance fact-checker))

  (-> {:stages [:a :b :c]
       :stage  :a}
      (adv [:init])
      (adv [:yes])
      (adv [:next-stage]))

  (automat.viz/view fact-checker)

  )

(def scripts {:onboard {:automat fact-checker
                        :stages [:company-name :company-description :currency :ceo]}
              :onboard-user {:automat init-only
                             :stages  [:init]}
              :onboard-user-authenticated {:automat init-only
                                           :stages  [:init]}
              :stakeholder-update {:automat init-only
                                   :stages [:init]}})

(defn initialize? [msg]
  (= :oc.bot/initialize (:type msg)))

(defn from-bot? [msg]
  (= "U10AR0H50" (:user msg)))

;; Very very simplistic...
(defn msg-text->transition [txt]
  (get {"yes" [:yes]
        "y"   [:yes]
        "no"  [:no]
        "n"   [:no]
        "€"   [:currency ::euro]
        "euro" [:currency ::euro]
        "EUR" [:currency ::euro]
        "$"   [:currency ::euro]
        "dollar" [:currency ::euro]
        "USD" [:currency ::euro]}
       txt
       [:str txt]))

(defn message-segment-id
  "Given `fsm-value` and it's most recent `signal`, find messages that should be sent"
  [fsm-value signal]
  (cond
    (and (= signal :yes) (get (:updated fsm-value) (:stage fsm-value)))
    [(:stage fsm-value) :yes-after-update]

    :else [(:stage fsm-value) signal]))

(defn messages [fsm [transition-signal]]
  (let [seg-id     (message-segment-id (:value fsm) transition-signal)
        msg-params (merge (-> fsm :value :init-msg :script :params) (-> fsm :value :updated))]
    (m/messages-for (-> fsm :value :script) seg-id msg-params)))

(defn stage-confirmed? [fsm]
  (contains? (-> fsm :value :confirmed) (-> fsm :value :stage)))

(defn trace [x] (prn x) x)

;; using this as a pure fn isn't such a great idea after all
;; when using things like `connect-via` deferreds should be returned
;; and using this function with `swap!` makes this harder
(defn test-transition-fn [fsm-state out-stream msg]
  (if (from-bot? msg)
    fsm-state
    (if (initialize? msg)
      (let [->full-msg (fn [text] {:type "message" :text text :channel (-> msg :receiver :id)})
            script-id  (-> msg :script :id)
            transition [:init]
            new-fsm    (a/advance (get-in scripts [script-id :automat])
                                  {:script   script-id
                                   :stage    (-> scripts script-id :stages first)
                                   :init-msg msg
                                   :stages   (-> scripts script-id :stages)}
                                  transition)]
        (timbre/info "Starting new scripted conversation:" script-id)
        (doseq [m' (messages new-fsm transition)]
          (s/put! out-stream (->full-msg m')))
        new-fsm)
      (let [->full-msg  (fn [text] {:type "message" :text text :channel (:channel msg)})
            transition  (msg-text->transition (:text msg))
            updated-fsm (a/advance (:onboard automats) fsm-state transition ::invalid)]
        (timbre/debug "Transition:" transition)
        ;; Side effects
        (if (= ::invalid updated-fsm)
          (s/put! out-stream (->full-msg (str "Sorry, " (-> fsm-state :value :init-msg :script :params :name)
                                              ". I'm not sure what to do with this.")))
          (doseq [m' (messages updated-fsm transition)]
            (s/put! out-stream (->full-msg m'))))
        ;; Return new FSM state
        ;; if the current stage is confirmed/completed also advance to next stage
        (trace (cond
                 (= ::invalid updated-fsm)      fsm-state
                 (stage-confirmed? updated-fsm) (a/advance (:onboard automats) updated-fsm [:next-stage])
                 :else                          updated-fsm))))))

;; TODO
;; 1. nice-to-have: connect streams so Conversations can put!/take! text only messages
;; 2. nice-to-have: make 'typing...' work without conversations needing to worry about it
;; 3. make sure put! return values are checked where necessary
;; 4. (maybe) remove need for multiple conversation managers
;; 5. 

;; -----------------------------------------------------------------------------
;; Conversation Routing 
;; Route messages to their respective conversations or create new conversations
;; -----------------------------------------------------------------------------

(defn mk-conv [out]
  (map->Conversation {:in  (s/stream)
                      :out out
                      :init-state {}
                      :transition-fn test-transition-fn}))

(defn message? [msg]
  (= "message" (:type msg)))

(defn msg->predicate
  "Build a predicate that can be used to figure out if messages are relevant for a conversation."
  [base-msg]
  ;; (prn 'msg->pred base-msg)
  (when (initialize? base-msg)
    (fn [msg]
      (and (not (from-bot? msg))
           (message? msg)
           (= (:channel msg)
              (-> base-msg :receiver :id))))))

;; TODO add tests
(defn find-matching-conv [convs msg]
  (let [[f s] (u/predicate-map-lookup convs msg)]
    (when s (timbre/debug "predicate-map-lookup returned multiple results, using first"))
    (when f (timbre/debugf "predicate-match for: %s\n" msg))
    f))

;; (defn not-ended
;;   "Return `conv`, but only if it's FSM isn't in an accepted state."
;;   [conv]
;;   (when conv
;;     (when-not (-> conv :state deref :fsm :accepted?)
;;       conv)))

;; Maybe the map created here should just have a channel as value
;; The created conversation could take messages from that channel
;; and handle all the rest
(defn dispatch! [conv-mngr incoming-msg]
  ;; (prn conv-mngr)
  (timbre/debugf "Number of ongoing conversations: %s\n" (count @(:conversations conv-mngr)))
  (if-let [conv (find-matching-conv @(:conversations conv-mngr) incoming-msg)]
    (s/put! (:in conv) incoming-msg)
    (let [new-conv (component/start (mk-conv (:out conv-mngr)))
          pred     (msg->predicate incoming-msg)]
      (when pred
        (timbre/infof "Registering new conversation %s\n" incoming-msg)
        (swap! (:conversations conv-mngr)
               assoc
               (msg->predicate incoming-msg)
               new-conv)
        (s/put! (:in new-conv) incoming-msg)))))

(defn conversation-manager [in out]
  (map->ConversationManager {:in in :out out :dispatcher dispatch!}))

(comment
  (do
    (def in (s/stream))

    (def out (s/stream))

    (s/consume prn out)

    (def init-msg
      {:type :oc.bot/initialize
       :script {:id :onboard
                :params {:name "Sarah" :company-name "Flickr" :company-description "Hottest startup on the block." :company-dashboard "https://opencompany.com/flickr" :contact-person "Tom"}}})

    (def conv-mngr
      (map->ConversationManager {:in in :out out
                                 :dispatcher dispatch!}))

    (alter-var-root #'conv-mngr component/start))

  (alter-var-root #'conv-mngr component/stop)

  (s/put! in init-msg)
  (s/put! in {:text "no" :channel 1})
  (s/put! in {:text "Amen" :channel 1})
  (s/put! in {:text "yes" :channel 1})

  (let [m {:a 1, :b 2, :c 3}
        v {:a 1 :c 9}]
    (u/predicate-map-lookup m v)))

;; experiment with aritificially delaying messages while maintaining order

(comment

  (def msg-id (atom 0))
  (defn msg [] [(* (rand-int 4) 1000) (swap! msg-id inc)])

  @(s/put! in (msg))

  (quot 4300 500)

  (mod 4300 500)

  (defn put-while
    "Send `msg` messages to out for `ms`"
    [ms every-ms out]
    (doseq [n (range (quot ms every-ms))]
      (Thread/sleep every-ms)
      (s/try-put! out :typing every-ms))
    (Thread/sleep (mod ms every-ms)))

  (do 
    (def in (s/stream 15))
    (def out (s/stream))

    (defn with-wait [msg]
      (-> msg
          (d/chain
           #(d/future (send-typing (first %) 300 out) %)
           #(update % 1 inc)
           #(do (prn {:result %}) %)
           #(s/put! out %)
           #(when-not % (throw (ex-info "Failed to out message" {:out out, :msg msg}))))
          (d/catch Exception #(throw %))))

    (s/connect-via in with-wait out)
    (s/consume #(prn 'out (.getSecond (LocalDateTime/now)) %) out))
  
  (prn 'x)
  )

;; experiment with using streams to reduce amount of info passed to conversations

(comment
  (s/connect-via
   in
   (fn [msg-full]
     (let [enrich (fn [txt] (merge {:text txt} (select-keys msg-full [:channel])))])
     )
   out)

  (let [msg {:channel 1 :text "abc"}
        src (s/stream)
        out (s/stream)
        trimmed (s/map :text src)
        enriched (s/map #(merge {:text %} (select-keys msg [:channel])) out)]
    ;; (s/consume #(prn 'out %) out)
    (s/consume #(prn 'enriched %) enriched)
    (s/consume #(prn 'trimmed %) trimmed)
    (s/connect-via trimmed #(apply d/chain (mapv (fn [c] (s/put! out c)) %)) out)
    (s/put! src msg)))