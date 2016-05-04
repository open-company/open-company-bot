(set-env!
 :source-paths #{"src"}
 :resource-paths #{"resources"}
 :dependencies '[[org.clojure/clojure "1.8.0"]
                 ;; Managed lifecycle of stateful objects in Clojure
                 ;; https://github.com/stuartsierra/component
                 [com.stuartsierra/component "0.3.1"]
                 ;; Pure Clojure/Script logging library
                 ;; https://github.com/ptaoussanis/timbre
                 [com.taoensso/timbre "4.3.1"]
                 ;; A Clojure implementation of Mustache
                 ;; https://github.com/davidsantiago/stencil
                 [stencil "0.5.0"]
                 ;; A comprehensive Clojure client for the entire Amazon AWS api.
                 ;; https://github.com/mcohen01/amazonica
                 [amazonica "0.3.53"]
                 ;; Asynchronous communication for clojure (http-client)
                 ;; https://github.com/ztellman/aleph
                 [aleph "0.4.2-alpha1"]
                 ;; Async programming tools (streams/deferred computation)
                 ;; https://github.com/ztellman/manifold
                 [manifold "0.1.4"]
                 ;; Finite state machines
                 ;; https://github.com/ztellman/automat
                 [automat "0.2.0-alpha2"]
                 ;; JSON encoding/decoding
                 ;; https://github.com/dakrone/cheshire
                 [cheshire "5.6.1"]
                 ;; Lightweight utility library
                 ;; https://github.com/weavejester/medley
                 [medley "0.7.4"]
                 ;; Environment variables
                 ;; https://github.com/weavejester/environ
                 [environ "1.0.2"]
                 ;; Boot tasks ==========================================
                 [boot-environ "1.0.2" :scope "test"] ; environ integration
                 [adzerk/boot-test "1.1.1" :scope "test"] ; clojure.test runner
                 [danielsz/boot-runit "0.1.0-SNAPSHOT"] ; runit supervisor integration
                 ])

(require '[environ.boot :refer [environ]]
         '[adzerk.boot-test :refer [test]]
         '[danielsz.boot-runit :refer [runit]])

(def config (read-string (slurp "config.edn")))

(deftask dev []
  (comp (environ :env config)
        (repl)))

(deftask test! []
  (set-env! :source-paths #(conj % "test"))
  (test :requires #{'amazonica.aws.sqs
                    'taoensso.timbre
                    'manifold.stream
                    'manifold.deferred
                    'stencil.core
                    'stencil.parser}))

(deftask build! []
  (comp (pom :project 'oc/bot
             :version "0.1.0")
        (aot :namespace #{'oc.bot})
        (uber)
        (jar :file "oc-bot.jar"
             :main 'oc.bot)
        (runit :env config
               :project "oc/bot")
        (target)))