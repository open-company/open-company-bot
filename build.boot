;; boot show --updates
(set-env!
 :source-paths #{"src"}
 :resource-paths #{"resources"}
 :dependencies '[[org.clojure/clojure "1.9.0-alpha10"]
                 ;; Managed lifecycle of stateful objects in Clojure
                 ;; https://github.com/stuartsierra/component
                 [com.stuartsierra/component "0.3.1"]
                 ;; Pure Clojure/Script logging library
                 ;; https://github.com/ptaoussanis/timbre
                 [com.taoensso/timbre "4.7.3"]
                 ;; Interface to Sentry error reporting
                 ;; https://github.com/sethtrain/raven-clj
                 [raven-clj "1.4.2"]
                 ;; A Clojure implementation of Mustache
                 ;; https://github.com/davidsantiago/stencil
                 [stencil "0.5.0"]
                 ;; A comprehensive Clojure client for the entire Amazon AWS api.
                 ;; https://github.com/mcohen01/amazonica
                 [amazonica "0.3.73"]
                 ;; Asynchronous communication for clojure (http-client)
                 ;; https://github.com/ztellman/aleph
                 [aleph "0.4.2-alpha6"]
                 ;; Apache Commons Validator provides the building blocks for validation.
                 ;; https://commons.apache.org/proper/commons-validator/
                 [commons-validator "1.5.1"]
                 ;; Async programming tools (streams/deferred computation)
                 ;; https://github.com/ztellman/manifold
                 [manifold "0.1.6-alpha1"]
                 ;; Finite state machines
                 ;; https://github.com/ztellman/automat
                 [automat "0.2.0-alpha2"]
                 ;; JSON encoding/decoding
                 ;; https://github.com/dakrone/cheshire
                 [cheshire "5.6.3"]
                 ;; Lightweight utility library
                 ;; https://github.com/weavejester/medley
                 [medley "0.8.2"]
                 ;; Environment variables
                 ;; https://github.com/weavejester/environ
                 [environ "1.1.0"]
                 ;; Boot tasks ==========================================
                 [boot-environ "1.1.0" :scope "test"] ; environ integration
                 [adzerk/boot-test "1.1.2" :scope "test"] ; clojure.test runner
                 ])

(require '[clojure.java.io :as io]
         '[environ.boot :refer [environ]]
         '[adzerk.boot-test :refer [test]])

(def config
  (let [f (io/file "config.edn")]
    (if (.exists f)
      (-> f slurp read-string)
      {})))

(deftask start []
  (comp (environ :env config)
        (with-pre-wrap fs
          (require 'oc.bot 'com.stuartsierra.component)
          ((resolve 'com.stuartsierra.component/start)
           ((resolve 'oc.bot/system) {:sqs-queue       (:aws-sqs-queue config)
                                      :sqs-msg-handler (resolve 'oc.bot/sqs-handler)}))
          fs)
        (wait)))

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
  (let [v        "0.1.0"
        jar-name (str "oc-bot-" v ".jar")]
    (comp (pom :project 'oc/bot :version v)
          (aot :namespace #{'oc.bot})
          (uber)
          (jar :main 'oc.bot :file jar-name)
          (sift :include #{(re-pattern (str "^" jar-name "$"))})
          (target))))