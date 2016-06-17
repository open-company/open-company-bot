(ns oc.bot.conversation
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [manifold.time :as t]
            [automat.core :as a]
            [clojure.string :as string]
            [medley.core :as med]
            [oc.bot.fsm :as fsm]
            [oc.bot.message :as m]
            [oc.bot.slack-api :as slack-api]
            [oc.bot.language :as lang]
            [oc.bot.utils :as u])
  (:import [java.time LocalDateTime]))

(defn for-duration-chain
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
          typing    {:type "typing" :channel (:channel msg)}]
      (-> (apply d/chain
                 nil
                 ;; 3000 here is a limit by Slack. When sending more than a few
                 ;; messages within a 3000ms window Slack will close the connection.
                 (conj (for-duration-chain typing wait-time 3000 out)
                       (fn [_] (s/put! out msg))
                       (fn [success?] (when-not success?
                                        (throw (ex-info "Failed to out message" {:out out, :msg msg}))))))
          (d/catch Exception #(throw %))))))

(defrecord ConversationManager [in out dispatcher]
  component/Lifecycle
  (start [component]
    (timbre/info "Starting Conversation Manager")
    (let [conversations (atom {})
          started       (assoc component :conversations conversations)]
      (s/on-closed in #(timbre/info "ConversationManager Source has been closed"))
      (s/connect-via in #(dispatcher conversations out %) out)
      started))
  (stop [component]
    (timbre/info "Stopping Conversation Manager")
    (dissoc component :conversations)))

;; -----------------------------------------------------------------------------
;; Define scripts by providing a state machine and it's stages
;; -----------------------------------------------------------------------------

(def scripts
  (let [init-only {:fsm    fsm/init-only-fsm
                   :stages [:init]}]
    {:onboard {:fsm    fsm/onboard-fsm
               :stages [:company/logo]}
     :onboard-user init-only
     :onboard-user-authenticated init-only
     :stakeholder-update init-only}))

;; -----------------------------------------------------------------------------
;; A few handy predicate functions
;; -----------------------------------------------------------------------------

(defn initialize? [msg]
  (boolean (and (= :oc.bot/initialize (:type msg))
                (-> msg :receiver :id))))

(defn transitions [txt]
  {lang/yes?                [:yes]
   lang/no?                 [:no]
   lang/not-now?            [:not-now]
   lang/euro?               [:currency ::eur]
   lang/dollar?             [:currency ::usd]
   lang/downloadable-image? [:image-url (lang/extract-url txt)]
   identity                 [:str txt]})

(defn msg-text->transition
  "Given a users message `txt` and a set of `allowed?` signals
   find a transition or return `nil`. Since all entries in the
   `transitions` table have equal weight it is very important that
   the list of allowed transitions is as constrained as possible."
  [txt allowed?]
  (let [trns (transitions txt)
        txt' (string/lower-case txt)]
    (-> (comp allowed? first)
        (med/filter-vals trns)
        (u/predicate-map-lookup txt')
        (first))))

(defn message-segment-id
  "Given `fsm-value` and it's most recent `signal`, return the [stage signal]
   tuple that should be used to look up messages."
  [fsm-value signal]
  (cond
    (and (= signal :yes) (get (:updated fsm-value) (:stage fsm-value)))
    [(:stage fsm-value) :yes-after-update]
    :else
    [(:stage fsm-value) signal]))

(defn messages
  [fsm-value [transition-signal]]
  (let [seg-id     (message-segment-id fsm-value transition-signal)
        msg-params (merge (-> fsm-value :init-msg :script :params) (-> fsm-value :updated))]
    (m/messages-for (-> fsm-value :script-id) seg-id msg-params)))

(defn stage-confirmed? [fsm]
  (contains? (-> fsm :value :confirmed) (-> fsm :value :stage)))

(def not-understood
  {:yes "You can answer with *yes* or *no*."
   :no "You can answer with *yes* or *no*."
   :currency "You can provide a currency with *EUR* or *USD*."
   :image-url "Please provide a link to an image that is publicly accessibly."})

(defn init-state [init-msg]
  (let [script-id (-> init-msg :script :id)
        team-info (slack-api/get-team-info (-> init-msg :bot :token))]
    {:script-id script-id
     :stage     (-> scripts script-id :stages first)
     :updated   (when (and (= :onboard script-id) (-> team-info :icon :image_default not))
                  {:company/logo (-> team-info :icon :image_132)})
     :init-msg  init-msg
     :stages    (-> scripts script-id :stages)}))

(defn transition-fn
  "Inputs:
   - `fsm-atom`, Atom containing the current state of the FSM representing the conversation
   - `out-stream`, a stream where messages should be put to send them back to the user
   - `msg`, a message that was identified as relevant to the conversation

  `msg` may either be a Slack event (usually messages) or an init message fetched from SQS
  If it is an init message the `fsm-atom` will get initialized with the appropriate FSM already
  transitioned with `[:init]`. It may also send initial messages to the user starting the conversation.

  If `msg` is not an init message from SQS it will be treated as a text message received by the user.
  If the message can be interpreted as one of the possible inputs to advance the FSM the `fsm-atom`
  will get updated. If not a 'I don't understand' message will be sent to the user indicating possible
  messages to advance the FSM.

  Additionally it is checked if the transition completes the current stage of the FSM, if it does
  it's state will be updated to be in the next stage."
  [fsm-atom out-stream msg]
  (if (initialize? msg)
    ;; Startup case, i.e. messages coming from SQS initiating new convs ========
    (let [->full-msg (fn [text] {:type "message" :text text :channel (-> msg :receiver :id)})
          script-id  (-> msg :script :id)
          transition [:init]
          new-fsm    (a/advance (get-in scripts [script-id :fsm])
                                (init-state msg)
                                transition)]
      (timbre/info "Starting new scripted conversation:" script-id)
      (reset! fsm-atom new-fsm)
      (doseq [m' (messages (:value new-fsm) transition)]
        (s/put! out-stream (->full-msg m')))
      (d/success-deferred true)) ; use `drain-into` coming in manifold 0.1.5

    ;; Regular case, i.e. messages sent by users =============================
    (let [->full-msg   (fn [text] {:type "message" :text text :channel (:channel msg)})
          compiled-fsm (get-in scripts [(-> @fsm-atom :value :script-id) :fsm])
          allowed?     (fsm/possible-transitions compiled-fsm @fsm-atom)
          transition   (msg-text->transition (:text msg) allowed?)]
      (timbre/info "Transitioning" {:message msg :transition transition})
      (let [updated-fsm  (a/advance compiled-fsm @fsm-atom transition ::invalid)]
        ;; Side effects
        (if (= ::invalid updated-fsm)
          (do
            (timbre/info "Message could not be turned into allowed signal"
                          {:allowed? allowed?
                           :fsm-state @fsm-atom
                           :msg msg
                           :transition transition})
            (s/put! out-stream (->full-msg (str "Sorry, " (-> @fsm-atom :value :init-msg :script :params :user/name)
                                                ". I'm not sure what to do with this.")))
            (if-let [guide-msg (not-understood (first allowed?))]
              (s/put! out-stream (->full-msg guide-msg)))
            (d/success-deferred true)) ; use `drain-into` coming in manifold 0.1.5
          (do
            (if (and (stage-confirmed? updated-fsm)
                     (not (:accepted? updated-fsm)))
              (reset! fsm-atom (a/advance compiled-fsm updated-fsm [:next-stage]))
              (reset! fsm-atom updated-fsm))
            (if (:error (:value updated-fsm))
              (s/put! out-stream (->full-msg "Sorry, something broke. We're on it. Please try again later."))
              (doseq [m' (messages (:value updated-fsm) transition)]
                (s/put! out-stream (->full-msg m'))))
            (d/success-deferred true))))))) ; use `drain-into` coming in manifold 0.1.5

;; -----------------------------------------------------------------------------
;; Conversation Routing 
;; Route messages to their respective conversations or create new conversations
;; -----------------------------------------------------------------------------

(defn msg->predicate
  "Build a predicate that can be used to figure out if messages are relevant for a conversation."
  [base-msg]
  (when (initialize? base-msg)
    (fn [msg]
      (and (not= (:subtype msg) "bot_message")
           (not= (:user msg) (-> base-msg :bot :id))
           (= (:type msg) "message")
           (= (:channel msg)
              (-> base-msg :receiver :id))))))

(defn find-matching-conv [convs msg]
  (let [[f s] (vec (u/predicate-map-lookup convs msg))]
    (when s (timbre/warn "predicate-map-lookup returned multiple results, using first"))
    (when f (timbre/debug "predicate-match for:" msg))
    f))

(defn mk-conv [out]
  (let [state (atom nil)]
    (partial transition-fn state out)))

(defn dispatch!
  "Use the `conversations` atom containing a predicate-map to find a conversation
  `incoming-msg` is relevant to or, given a predicate can be derived from `incoming-msg`,
  create a new conversation piping it's messages into `out` with a length-depending delay."
  [conversations out incoming-msg]
  (timbre/debugf "Number of ongoing conversations: %s\n" (count @conversations))
  (if-let [conv-in (find-matching-conv @conversations incoming-msg)]
    (s/put! conv-in incoming-msg)
    (let [conv-in  (s/stream)
          conv-out (s/stream)]
      (if-let [pred (msg->predicate incoming-msg)]
        (do
          (timbre/info "Registering new conversation" incoming-msg)
          (s/connect-via conv-in (mk-conv conv-out) conv-out)
          ;; TODO consider making conv-out buffered
          (s/connect-via conv-out (with-wait out) out)
          (swap! conversations assoc pred conv-in)
          (s/put! conv-in incoming-msg))
        ;; return a success deferred to keep connect-via happy
        (d/success-deferred true)))))

(defn conversation-manager [in out]
  (map->ConversationManager {:in in :out out :dispatcher dispatch!}))

(comment
  (do
    (def in (s/stream))

    (def out (s/stream))

    (s/consume prn out)

    (def init-msg
      {:type :oc.bot/initialize
       :receiver {:type :channel :id 1}
       :script {:id :onboard
                :params {:user/name "Sarah" :company/name "Flickr" :company/currency "USD" :company/slug "flickr"
                         :company/description "Hottest startup on the block." :contact-person "Tom"}}})

    (def conv-mngr
      (map->ConversationManager {:in in :out out
                                 :dispatcher dispatch!}))

    (alter-var-root #'conv-mngr component/start))

  (alter-var-root #'conv-mngr component/stop)

  (s/put! in init-msg)
  (s/put! in {:type "message" :text "wahaay" :channel 1})
  (s/put! in {:type "message" :text "Amen" :channel 1})
  (s/put! in {:type "message" :text "yes" :channel 1})
  (s/put! in {:type "message" :text "no" :channel 1})
  (s/put! in {:type "message" :text "Yolo, Yahoo." :channel 1})

  (s/put! in "hi")
  (s/put! in "yo")
  (s/put! in "bye")

  (s/drain-into [:a :b :c] in)
  

  (let [m {:a 1, :b 2, :c 3}
        v {:a 1 :c 9}]
    (u/predicate-map-lookup m v))


  (f/alphabet (:fsm (meta (get-in scripts [:onboard :fsm]))))

  (a/advance fact-checker nil nil)

  )