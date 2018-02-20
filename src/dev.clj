(ns dev
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as ctnrepl]
            [oc.lib.db.pool :as pool]
            [oc.bot.config :as c]
            [oc.bot.async.bot :as bot]
            [oc.bot.components :as components]
            [oc.bot.app :as app]))

(def system nil)
(defonce conn nil)

(defn init [] (alter-var-root #'system (constantly (components/bot-system {:sqs-queue c/aws-sqs-bot-queue
                                                                           :sqs-msg-handler bot/sqs-handler
                                                                           :sqs-creds {:access-key c/aws-access-key-id
                                                                                       :secret-key c/aws-secret-access-key}}))))

(defn bind-conn! []
  (alter-var-root #'conn (constantly (pool/claim (get-in system [:db-pool :pool])))))

(defn- start⬆ []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (component/stop s))))
  (println (str "When you're ready to start the system again, just type: (go)\n")))

(defn go []
  (init)
  (start⬆)
  (bind-conn!)
  (app/echo-config)
  (println (str "Now serving bot requests from the REPL.\n"
                "A DB connection is available with: conn\n"
                "When you're ready to stop the system, just type: (stop)\n"))
  :ok)

(defn reset []
  (stop)
  (ctnrepl/refresh :after 'user/go))