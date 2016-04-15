(set-env!
 :source-paths #{"src"}
 :resource-paths #{"resources"}
 :dependencies '[[org.clojure/clojure "1.8.0"]
                 [com.stuartsierra/component "0.3.1"]
                 ;; [stylefruits/gniazdo "0.4.1"]
                 [stencil "0.5.0"]
                 [amazonica "0.3.53"]
                 [aleph "0.4.1"]
                 [cheshire "5.6.1"]
                 [environ "1.0.2"]
                 [boot-environ "1.0.2" :scope "test"]])

(require '[environ.boot :refer [environ]])

(def config (read-string (slurp "config.edn")))

(deftask dev []
  (comp (environ :env config)
        (repl)))

