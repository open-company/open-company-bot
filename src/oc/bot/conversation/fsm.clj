(ns oc.bot.conversation.fsm
  (:require [automat.core :as a]
            [automat.fsm :as f]
            [taoensso.timbre :as timbre]
            [oc.bot.utils :as u]
            [oc.api-client :as api]))

(defn dry-run-wrap
  "Wrap `action-fn` so that it only calls `action-fn` if the FSM's state
   does not contain a truthy value under `::dry-run`."
  [action-fn]
  (fn [state input]
    (if (::dry-run state)
      state
      (action-fn state input))))

(defn possible-transitions [compiled-fsm state]
  (let [alphabet (f/alphabet (:fsm (meta compiled-fsm)))
        dry-run  (assoc state ::dry-run true)]
    ;; SIGNAL using [t] here means we assume the FSMs signal function is `first`
    (set (filter (fn [t] (a/advance compiled-fsm dry-run [t] false)) alphabet))))

(defn confirm-fn [{:keys [stage] :as state} _]
  (timbre/info "Confirming update" state)
  (if-let [updated (get-in state [:updated stage])]
    (if @(api/patch-company! (-> state :init-msg :api-token)
                             (-> state :init-msg :script :params :company/slug)
                             {(-> stage name keyword) updated})
      (update state :confirmed (fnil conj #{}) stage)
      (update state :error (fnil conj #{}) stage))
    (update state :confirmed (fnil conj #{}) stage)))

(defn fact-check [update-transition]
  [(a/or
    [:yes (a/$ :confirm)]
    [(a/+ [:no [update-transition (a/$ :update)]])
     [:yes (a/$ :confirm)]])])

(defn optional-input [update-transition]
  [(a/or
    [:no]
    [update-transition (a/$ :update)
     [:yes (a/$ :confirm)]])])

(def init-only-fsm (a/compile [:init] {:signal first}))

(def onboard-fsm
  (a/compile [:init
              (fact-check :str) ;name
              [:next-stage (a/$ :next-stage)]]
             {:signal   first
              :reducers {:next-stage (dry-run-wrap (fn [state input] (update state :stage (fn [s] (u/next-in (:stages state) s)))))
                         :confirm    (dry-run-wrap confirm-fn)
                         :update     (dry-run-wrap (fn [state [sig v]] (assoc-in state [:updated (:stage state)] v)))}}))

(comment
  (def adv (partial a/advance onboard-fsm))

  (-> {:stages [:a :b :c]
       :stage  :a}
      (adv [:init])
      (adv [:yes])
      (adv [:next-stage]))

  (require 'automat.viz)

  (automat.viz/view (fact-check :str))

  )