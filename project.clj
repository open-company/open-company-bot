(defproject open-company-bot "0.2.0-SNAPSHOT"
  :description "OpenCompany Bot Service"
  :url "https://opencompany.com/"
  :license {
    :name "Mozilla Public License v2.0"
    :url "http://www.mozilla.org/MPL/2.0/"
  }

  :min-lein-version "2.5.1" ; highest version supported by Travis-CI as of 1/28/2016

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx3072m" "-server"]

  ;; All profile dependencies
  :dependencies [
    [org.clojure/clojure "1.9.0-alpha14"] ; Lisp on the JVM http://clojure.org/documentation
    [org.clojure/core.async "0.2.395"] ; Async programming and communication https://github.com/clojure/core.async
    [environ "1.1.0"] ; Environment settings from different sources https://github.com/weavejester/environ
    [com.taoensso/timbre "4.8.0-alpha1"] ; Logging https://github.com/ptaoussanis/timbre
    [raven-clj "1.5.0"] ; Interface to Sentry error reporting https://github.com/sethtrain/raven-clj
    [com.stuartsierra/component "0.3.1"] ; Component Lifecycle https://github.com/stuartsierra/component
    [funcool/cuerdas "2.0.0"] ; String manipulation library https://github.com/funcool/cuerdas
    [medley "0.8.3"] ; Lightweight utility library https://github.com/weavejester/medley
    [cheshire "5.6.3"] ; JSON encoding / decoding https://github.com/dakrone/cheshire
    [automat "0.2.0"] ; Finite state machines https://github.com/ztellman/automat
    [commons-validator "1.5.1"] ; provides the building blocks for validation https://commons.apache.org/proper/commons-validator/
    [stencil "0.5.0"] ; Clojure implementation of Mustache https://github.com/davidsantiago/stencil
    [clj-time "0.12.2"] ; Date and time lib https://github.com/clj-time/clj-time
    [clj-http "3.3.0"] ; HTTP client https://github.com/dakrone/clj-http
    [aleph "0.4.2-alpha8"] ; Asynch comm. for clojure (http-client) https://github.com/ztellman/aleph don't upgrade to 0.4.2-alpha
    [manifold "0.1.6-alpha3"] ; Async programming tools https://github.com/ztellman/manifold
    [open-company/lib "0.0.6-04c024d"] ; Library for OC projects https://github.com/open-company/open-company-lib
  ]

  ;; All profile plugins
  :plugins [
    [lein-environ "1.1.0"] ; Get environment settings from different sources https://github.com/weavejester/environ
  ]

  :profiles {

    ;; QA environment and dependencies
    :qa {
      :env {
      }
      :dependencies [
        [philoskim/debux "0.2.1"] ; `dbg` macro around -> or let https://github.com/philoskim/debux
      ]
      :plugins [
        [jonase/eastwood "0.2.3"] ; Linter https://github.com/jonase/eastwood
        [lein-kibit "0.1.2"] ; Static code search for non-idiomatic code https://github.com/jonase/kibit
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :aws-sqs-bot-queue "https://sqs.REGION.amazonaws.com/CHANGE/ME"
        :oc-api-endpoint "http://localhost:3000"
        :intro "true"
        :log-level "trace"
      }
      :plugins [
        [lein-bikeshed "0.4.0"] ; Check for code smells https://github.com/dakrone/lein-bikeshed
        [lein-checkall "0.1.1"] ; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-pprint "1.1.2"] ; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-ancient "0.6.10"] ; Check for outdated dependencies https://github.com/xsc/lein-ancient
        [lein-spell "0.1.0"] ; Catch spelling mistakes in docs and docstrings https://github.com/cldwalker/lein-spell
        [lein-deps-tree "0.1.2"] ; Print a tree of project dependencies https://github.com/the-kenny/lein-deps-tree
        [venantius/yagni "0.1.4"] ; Dead code finder https://github.com/venantius/yagni
        [com.jakemccrary/lein-test-refresh "0.17.0"] ; Autotest https://github.com/jakemcc/lein-test-refresh
      ]  
    }]

    :repl-config [:dev {
      :dependencies [
        [org.clojure/tools.nrepl "0.2.12"] ; Network REPL https://github.com/clojure/tools.nrepl
        [aprint "0.1.3"] ; Pretty printing in the REPL (aprint ...) https://github.com/razum2um/aprint
      ]
      ;; REPL injections
      :injections [
        (require '[aprint.core :refer (aprint ap)]
                 '[clojure.stacktrace :refer (print-stack-trace)]
                 '[clojure.string :as s]
                 '[cheshire.core :as json])
      ]
    }]

    ;; Production environment
    :prod {}

    :uberjar {
      :aot :all
    }
  }

  :repl-options {
    :welcome (println (str "\n" (slurp (clojure.java.io/resource "oc/assets/ascii_art.txt")) "\n"
                      "OpenCompany Bot Service REPL\n"))
  }

  :aliases {
    "build" ["with-profile" "prod" "do" "clean," "uberjar"] ; clean and build code
    "repl" ["with-profile" "+repl-config" "repl"]
    "start" ["run" "-m" "oc.bot"] ; start a development server
    "start!" ["with-profile" "prod" "do" "start"] ; start a server in production
    "spell!" ["spell" "-n"] ; check spelling in docs and docstrings
    "bikeshed!" ["bikeshed" "-v" "-m" "120"] ; code check with max line length warning of 120 characters
    "ancient" ["ancient" ":all" ":allow-qualified"] ; check for out of date dependencies
  }

  :main oc.bot
)