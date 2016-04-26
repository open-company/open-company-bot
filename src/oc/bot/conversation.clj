(ns oc.bot.conversation
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [manifold.time :as t]
            [automat.core :as a]
            [automat.fsm :as f]
            [clojure.string :as string]
            [medley.core :as med]
            [oc.bot.message :as m]
            [oc.bot.language :as lang]
            [oc.bot.utils :as u])
  (:import [java.time LocalDateTime]))

(defn typing-chain
  "Return a vector of deferred emitting functions that will
   put `msg` into `out` for `ms` every `every.ms`
   Intended to be used with `manifold.deferred/chain`"
  [msg ms every-ms out]
  (mapv
   #(fn [_] (t/in (* % every-ms)
                  (fn [] (s/try-put! out msg every-ms))))
   (range (inc (quot ms every-ms)))))

(defn with-wait
  "Return a single function that will eventually put it's argument `msg`
   into `out` after sending a few 'typing' messages into out."
  [out]
  (fn [msg]
    (let [wait-time (* 50 (count (:text msg)))
          typing    {:type "typing" :channel (or (:channel msg) (-> msg :receiver :id))}]
      (-> (apply d/chain
                 nil
                 ;; 3000 here is a limit by Slack. When sending more than a few
                 ;; messages within a 3000ms window Slack will close the connection.
                 (conj (typing-chain typing wait-time 3000 out)
                       (fn [_] (s/put! out msg))
                       (fn [success?] (when-not success?
                                        (throw (ex-info "Failed to out message" {:out out, :msg msg}))))))
          (d/catch Exception #(throw %))))))

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

;; -----------------------------------------------------------------------------
;; Conversation state
;; Conversations as state machines. Coerce messages into transitions.
;; Build data to look up messages to be sent.
;; -----------------------------------------------------------------------------

;; TODO use this to limit msg->transition lookup space
(defn possible-transitions [compiled-fsm state]
  (let [alphabet (f/alphabet (:fsm (meta compiled-fsm)))]
    (set (filter (fn [t] (a/advance compiled-fsm state t nil)) alphabet))))

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

(def init-only {:automat (a/compile [:init] {:signal first})
                :stages  [:init]})

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
              :onboard-user init-only
              :onboard-user-authenticated init-only
              :stakeholder-update init-only})

(defn initialize? [msg]
  (= :oc.bot/initialize (:type msg)))

(defn from-bot? [msg]
  (= "U10AR0H50" (:user msg)))

(defn transitions [txt]
  {lang/yes?     [:yes]
   lang/no?      [:no]
   lang/euro?    [:currency ::eur]
   lang/dollar?  [:currency ::usd]
   (fn [_] true) [:str txt]})

(defn msg-text->transition
  "Given a users message `txt` and a set of `allowed?` signals
   find a transition or return nil"
  [txt allowed?]
  (let [trns (transitions txt)
        txt' (string/lower-case txt)]
    (-> (comp allowed? first)
        (med/filter-vals trns)
        (u/predicate-map-lookup txt')
        (first))))

(defn message-segment-id
  "Given `fsm-value` and it's most recent `signal`, find messages that should be sent"
  [fsm-value signal]
  (cond
    (and (= signal :yes) (get (:updated fsm-value) (:stage fsm-value)))
    [(:stage fsm-value) :yes-after-update]
    :else
    [(:stage fsm-value) signal]))

(defn messages [fsm [transition-signal]]
  (let [seg-id     (message-segment-id (:value fsm) transition-signal)
        msg-params (merge (-> fsm :value :init-msg :script :params) (-> fsm :value :updated))]
    (m/messages-for (-> fsm :value :script-id) seg-id msg-params)))

(defn stage-confirmed? [fsm]
  (contains? (-> fsm :value :confirmed) (-> fsm :value :stage)))

(defn trace [x] (prn x) x)

(defn transition-fn [fsm-atom out-stream msg]
  (if (from-bot? msg)
    (d/success-deferred true)
    (if (initialize? msg)

      ;; Startup case, i.e. messages coming from SQS initiating new convs ========
      (let [->full-msg (fn [text] {:type "message" :text text :channel (-> msg :receiver :id)})
            script-id  (-> msg :script :id)
            transition [:init]
            new-fsm    (a/advance (get-in scripts [script-id :automat])
                                  {:script-id script-id
                                   :stage    (-> scripts script-id :stages first)
                                   :init-msg msg
                                   :stages   (-> scripts script-id :stages)}
                                  transition)]
        (timbre/info "Starting new scripted conversation:" script-id)
        (reset! fsm-atom new-fsm)
        (doseq [m' (messages new-fsm transition)]
          (s/put! out-stream (->full-msg m')))
        (d/success-deferred true)) ; use `drain-into` coming in manifold 0.1.5
      
      ;; Regular case, i.e. messages sent by users =============================
      (let [->full-msg   (fn [text] {:type "message" :text text :channel (:channel msg)})
            compiled-fsm (get-in scripts [(-> @fsm-atom :value :script-id) :automat])
            transition   (msg-text->transition (:text msg) (possible-transitions compiled-fsm @fsm-atom))
            updated-fsm  (a/advance compiled-fsm @fsm-atom transition ::invalid)]
        (timbre/debug "Transition:" transition)
        ;; Side effects
        (if (= ::invalid updated-fsm)
          (do
            (s/put! out-stream (->full-msg (str "Sorry, " (-> @fsm-atom :value :init-msg :script :params :name)
                                                ". I'm not sure what to do with this.")))
            (d/success-deferred true))) ; use `drain-into` coming in manifold 0.1.5
          (do
            (if (stage-confirmed? updated-fsm)
              (reset! fsm-atom (a/advance compiled-fsm updated-fsm [:next-stage]))
              (reset! fsm-atom updated-fsm))
            (doseq [m' (messages updated-fsm transition)]
              (timbre/info "Sending:" m')
              (s/put! out-stream (->full-msg m')))
            (d/success-deferred true)))))) ; use `drain-into` coming in manifold 0.1.5

;; -----------------------------------------------------------------------------
;; Conversation Routing 
;; Route messages to their respective conversations or create new conversations
;; -----------------------------------------------------------------------------

(defn message? [msg]
  (= "message" (:type msg)))

(defn msg->predicate
  "Build a predicate that can be used to figure out if messages are relevant for a conversation."
  [base-msg]
  ;; (prn 'msg->pred base-msg)
  ;; (constantly true) ; handy when testing
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

(defn mk-conv [out]
  (let [state (atom nil)]
    (partial transition-fn state out)))

(defn dispatch! [conv-mngr incoming-msg]
  ;; (prn conv-mngr)
  (timbre/debugf "Number of ongoing conversations: %s\n" (count @(:conversations conv-mngr)))
  (if-let [conv-in (find-matching-conv @(:conversations conv-mngr) incoming-msg)]
    (s/put! conv-in incoming-msg)
    (let [conv-in  (s/stream)
          conv-out (s/stream)
          pred     (msg->predicate incoming-msg)]
      (when pred
        (timbre/infof "Registering new conversation %s\n" incoming-msg)
        (s/connect-via conv-in (mk-conv conv-out) conv-out)
        ;; TODO consider making conv-out buffered
        (s/connect-via conv-out (with-wait (:out conv-mngr)) (:out conv-mngr))
        (swap! (:conversations conv-mngr) assoc pred conv-in)
        (s/put! conv-in incoming-msg)))))

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

  (s/put! in "hi")
  (s/put! in "yo")
  (s/put! in "bye")

  (s/drain-into [:a :b :c] in)
  

  (let [m {:a 1, :b 2, :c 3}
        v {:a 1 :c 9}]
    (u/predicate-map-lookup m v)))