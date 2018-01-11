(defproject open-company-bot "0.2.0-SNAPSHOT"
  :description "OpenCompany Bot Service"
  :url "https://github.com/open-company/open-company-bot"
  :license {
    :name "Mozilla Public License v2.0"
    :url "http://www.mozilla.org/MPL/2.0/"
  }

  :min-lein-version "2.7.1"

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx3072m" "-server"]

  ;; All profile dependencies
  :dependencies [
    ;; Lisp on the JVM http://clojure.org/documentation
    [org.clojure/clojure "1.9.0"]
    ;; String manipulation library https://github.com/funcool/cuerdas
    [funcool/cuerdas "2.0.5"] 
    ;; Asynch comm. for clojure (http-client) https://github.com/ztellman/aleph
    [aleph "0.4.5-alpha2"]
    ;; Async programming tools https://github.com/ztellman/manifold
    [manifold "0.1.7-alpha6"]
    ;; Namespace management https://github.com/clojure/tools.namespace
    ;; NB: org.clojure/tools.reader pulled in by oc.lib
    [org.clojure/tools.namespace "0.3.0-alpha4" :exclusions [org.clojure/tools.reader]] 

    [clj-soup/clojure-soup "0.1.3"]

    ;; Library for OC projects https://github.com/open-company/open-company-lib
    [open-company/lib "0.14.15"]
    ;; In addition to common functions, brings in the following common dependencies used by this project:
    ;; core.async - Async programming and communication https://github.com/clojure/core.async
    ;; Component - Component Lifecycle https://github.com/stuartsierra/component
    ;; Timbre - Pure Clojure/Script logging library https://github.com/ptaoussanis/timbre
    ;; Amazonica - A comprehensive Clojure client for the AWS API. https://github.com/mcohen01/amazonica
    ;; Raven - Interface to Sentry error reporting https://github.com/sethtrain/raven-clj
    ;; Cheshire - JSON encoding / decoding https://github.com/dakrone/cheshire
    ;; clj-time - Date and time lib https://github.com/clj-time/clj-time
    ;; environ - Environment settings from different sources https://github.com/weavejester/environ  ]
  ]

  ;; All profile plugins
  :plugins [
    ;; Get environment settings from different sources https://github.com/weavejester/environ
    [lein-environ "1.1.0"]
  ]

  :profiles {

    ;; QA environment and dependencies
    :qa {
      :env {
      }
      :plugins [
        ;; Linter https://github.com/jonase/eastwood
        [jonase/eastwood "0.2.6-beta2"]
        ;; Static code search for non-idiomatic code https://github.com/jonase/kibit        
        [lein-kibit "0.1.6" :exclusions [org.clojure/clojure]]
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :aws-sqs-bot-queue "https://sqs.REGION.amazonaws.com/CHANGE/ME"
      }
      :plugins [
        ;; Check for code smells https://github.com/dakrone/lein-bikeshed
        ;; NB: org.clojure/tools.cli is pulled in by lein-kibit
        [lein-bikeshed "0.5.0" :exclusions [org.clojure/tools.cli]] 
        ;; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-checkall "0.1.1"]
        ;; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-pprint "1.2.0"]
        ;; Check for outdated dependencies https://github.com/xsc/lein-ancient
        [lein-ancient "0.6.15"]
        ;; Catch spelling mistakes in docs and docstrings https://github.com/cldwalker/lein-spell
        [lein-spell "0.1.0"]
        ;; Dead code finder https://github.com/venantius/yagni
        [venantius/yagni "0.1.4" :exclusions [org.clojure/clojure]]
        ;; Autotest https://github.com/jakemcc/lein-test-refresh
        [com.jakemccrary/lein-test-refresh "0.22.0"]
      ]  
    }]

    :repl-config [:dev {
      :dependencies [
        ;; Network REPL https://github.com/clojure/tools.nrepl
        [org.clojure/tools.nrepl "0.2.13"]
        ;; Pretty printing in the REPL (aprint ...) https://github.com/razum2um/aprint
        [aprint "0.1.3"]
      ]
      ;; REPL injections
      :injections [
        (require '[aprint.core :refer (aprint ap)]
                 '[clojure.stacktrace :refer (print-stack-trace)]
                 '[clojure.string :as s]
                 '[cheshire.core :as json]
                 '[clj-time.core :as t]
                 '[clj-time.coerce :as coerce]
                 '[clj-time.format :as f])
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