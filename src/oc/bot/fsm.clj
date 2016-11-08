(ns oc.bot.fsm
  (:require [automat.core :as a]
            [automat.fsm :as f]
            [taoensso.timbre :as timbre]
            [oc.bot.lib.utils :as u]
            [oc.lib.api-client :as api]))

(defn dry-run-wrap
  "Wrap `action-fn` so that it only calls `action-fn` if the FSM's state
   does not contain a truthy value under `::dry-run`."
  [action-fn]
  (fn [state input]
    ;; (timbre/debug "Dry run?" (::dry-run state) input)
    (if (::dry-run state)
      state
      (action-fn state input))))

(defn possible-transitions [compiled-fsm state]
  (let [alphabet (f/alphabet (:fsm (meta compiled-fsm)))
        dry-run  (assoc-in state [:value ::dry-run] true)]
    ;; SIGNAL using [t] here means we assume the FSMs signal function is `first`
    (-> #(do ;(timbre/debug "Testing transition" % "with state" dry-run)
             (a/advance compiled-fsm dry-run [%] false))
        (filter alphabet)
        (set))))

(defn confirm-fn [{:keys [stage] :as state} _]
  (timbre/info "Confirming update" state)
  (if-let [updated (get-in state [:updated stage])]
    (if @(api/patch-company! (-> state :init-msg :api-token)
                             (-> state :init-msg :script :params :company/slug)
                             {(-> stage name keyword) updated})
      (update state :confirmed (fnil conj #{}) stage)
      (update state :error (fnil conj #{}) stage))
    (update state :confirmed (fnil conj #{}) stage)))

(defn presence-branch [present missing]
  (a/or [::value-present present]
        [::value-missing missing]))

(defn advance-presence-branch
  "Advance the given FSM if the current `stage` can be found in the scripts `params`
   and the current possible transitions are presence branch actions
   `::vlaue-present` / `::value-missing`"
  [compiled-fsm {:keys [value] :as fsm-state}]
  (let [presence-branch? (= (possible-transitions compiled-fsm fsm-state)
                            #{::value-present ::value-missing})
        ;; TODO this could/should be passed to the function as a parameter
        val-present?     (-> (merge (get-in value [:init-msg :script :params])
                                    (get-in value [:updated]))
                             (get (-> value :stage)))]
    (cond
      (and presence-branch? val-present?) (a/advance compiled-fsm fsm-state [::value-present])
      (and presence-branch?)              (a/advance compiled-fsm fsm-state [::value-missing])
      :else                               fsm-state)))

(defn fact-check [update-transition]
  [(a/or
    [:yes (a/$ :confirm)]
    [(a/+ [:no [update-transition (a/$ :update)]])
     [:yes (a/$ :confirm)]])])

(defn skippable-fact-check [update-transition]
  [(a/or
    [:yes (a/$ :confirm)]
    [:no :not-now]
    [(a/+ [:no [update-transition (a/$ :update)]])
     [:yes (a/$ :confirm)]])])

(defn optional-input [update-transition]
  [(a/or [:not-now]
         [update-transition (a/$ :update)
          [:yes (a/$ :confirm)]])])

(def init-only-fsm (a/compile [:init] {:signal first}))

(def onboard-fsm
  (a/compile [:init
              ;; company/logo
              (presence-branch (skippable-fact-check :image-url)
                               (optional-input :image-url))
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

  (def pb (a/compile (presence-branch (fact-check :image-url)
                                      (optional-input :image-url))
                     {:signal first}))

  (f/alphabet (:fsm (meta pb)))

  (a/advance pb {} [::value-missing])

  (possible-transitions pb {})

  (advance-presence-branch pb {})

  (require 'automat.viz)

  (automat.viz/view (fact-check :str))
  (automat.viz/view (presence-branch (skippable-fact-check :image-url)
                                     (optional-input :image-url)))
  
  )