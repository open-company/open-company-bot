(defproject open-company-bot "0.2.0-SNAPSHOT"
  :description "OpenCompany Bot Service"
  :url "https://opencompany.com/"
  :license {
    :name "Mozilla Public License v2.0"
    :url "http://www.mozilla.org/MPL/2.0/"
  }

  :min-lein-version "2.7.1"

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx3072m" "-server"]

  ;; All profile dependencies
  :dependencies [
    [org.clojure/clojure "1.9.0-alpha17"] ; Lisp on the JVM http://clojure.org/documentation
    [funcool/cuerdas "2.0.3"] ; String manipulation library https://github.com/funcool/cuerdas
    [aleph "0.4.4-alpha4"] ; Asynch comm. for clojure (http-client) https://github.com/ztellman/aleph
    [manifold "0.1.7-alpha5"] ; Async programming tools https://github.com/ztellman/manifold
    [org.clojure/tools.namespace "0.3.0-alpha4"] ; Namespace management https://github.com/clojure/tools.namespace

    [open-company/lib "0.11.14"] ; Library for OC projects https://github.com/open-company/open-company-lib
    ; In addition to common functions, brings in the following common dependencies used by this project:
    ; core.async - Async programming and communication https://github.com/clojure/core.async
    ; Component - Component Lifecycle https://github.com/stuartsierra/component
    ; Timbre - Pure Clojure/Script logging library https://github.com/ptaoussanis/timbre
    ; Amazonica - A comprehensive Clojure client for the AWS API. https://github.com/mcohen01/amazonica
    ; Raven - Interface to Sentry error reporting https://github.com/sethtrain/raven-clj
    ; Cheshire - JSON encoding / decoding https://github.com/dakrone/cheshire
    ; clj-time - Date and time lib https://github.com/clj-time/clj-time
    ; environ - Environment settings from different sources https://github.com/weavejester/environ  ]
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
        [philoskim/debux "0.3.4"] ; `dbg` macro around -> or let https://github.com/philoskim/debux
      ]
      :plugins [
        [jonase/eastwood "0.2.4"] ; Linter https://github.com/jonase/eastwood
        [lein-kibit "0.1.5"] ; Static code search for non-idiomatic code https://github.com/jonase/kibit
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :aws-sqs-bot-queue "https://sqs.REGION.amazonaws.com/CHANGE/ME"
        :oc-api-endpoint "http://localhost:3000"
      }
      :plugins [
        [lein-bikeshed "0.4.1"] ; Check for code smells https://github.com/dakrone/lein-bikeshed
        [lein-checkall "0.1.1"] ; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-pprint "1.1.2"] ; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-ancient "0.6.10"] ; Check for outdated dependencies https://github.com/xsc/lein-ancient
        [lein-spell "0.1.0"] ; Catch spelling mistakes in docs and docstrings https://github.com/cldwalker/lein-spell
        [lein-deps-tree "0.1.2"] ; Print a tree of project dependencies https://github.com/the-kenny/lein-deps-tree
        [venantius/yagni "0.1.4"] ; Dead code finder https://github.com/venantius/yagni
        [com.jakemccrary/lein-test-refresh "0.20.0"] ; Autotest https://github.com/jakemcc/lein-test-refresh
      ]  
    }]

    :repl-config [:dev {
      :dependencies [
        [org.clojure/tools.nrepl "0.2.13"] ; Network REPL https://github.com/clojure/tools.nrepl
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
                      "OpenCompany Bot Service REPL\n"
                      "\nReady to do your bidding... I suggest (go) as your first command.\n"))
    :init-ns dev
  }

  :aliases {
    "build" ["with-profile" "prod" "do" "clean," "uberjar"] ; clean and build code
    "repl" ["with-profile" "+repl-config" "repl"]
    "start" ["run" "-m" "oc.bot.app"] ; start a development server
    "start!" ["with-profile" "prod" "do" "start"] ; start a server in production
    "spell!" ["spell" "-n"] ; check spelling in docs and docstrings
    "bikeshed!" ["bikeshed" "-v" "-m" "120"] ; code check with max line length warning of 120 characters
    "ancient" ["ancient" ":all" ":allow-qualified"] ; check for out of date dependencies
  }

  :main oc.bot.app
)