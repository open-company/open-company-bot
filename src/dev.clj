(ns dev
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as ctnrepl]
            [oc.bot.config :as c]
            [oc.bot.app :as app]))

(def system nil)

(defn init [] (alter-var-root #'system (constantly (app/system {:sqs-queue c/aws-sqs-bot-queue
                                                                :sqs-msg-handler app/sqs-handler
                                                                :sqs-creds {:access-key c/aws-access-key-id
                                                                            :secret-key c/aws-secret-access-key}}))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (component/stop s)))))

(defn go []
  (init)
  (start)
  (app/echo-config)
  (println (str "Now serving bot requests from the REPL.\n"
                "When you're ready to stop the system, just type: (stop)\n"))
  :ok)

(defn reset []
  (stop)
  (ctnrepl/refresh :after 'user/go))