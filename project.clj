(defproject open-company-bot "0.2.0-SNAPSHOT"
  :description "OpenCompany Bot Service"
  :url "https://github.com/open-company/open-company-bot"
  :license {
    :name "GNU Affero General Public License Version 3"
    :url "https://www.gnu.org/licenses/agpl-3.0.en.html"
  }

  :min-lein-version "2.7.1"

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx3072m" "-server"]

  ;; All profile dependencies
  :dependencies [
    ;; Lisp on the JVM http://clojure.org/documentation
    [org.clojure/clojure "1.10.1-RC1"]
    ;; Asynch comm. for clojure (http-client) https://github.com/ztellman/aleph
    [aleph "0.4.7-alpha5"]
    ;; Async programming tools https://github.com/ztellman/manifold
    [manifold "0.1.9-alpha3"]
    ;; Namespace management https://github.com/clojure/tools.namespace
    ;; NB: org.clojure/tools.reader pulled in by oc.lib
    [org.clojure/tools.namespace "0.3.0-alpha4" :exclusions [org.clojure/tools.reader]] 

    ;; Library for OC projects https://github.com/open-company/open-company-lib
    [open-company/lib "0.17.9"]
    ;; In addition to common functions, brings in the following common dependencies used by this project:
    ;; defun - Erlang-esque pattern matching for Clojure functions https://github.com/killme2008/defun
    ;; core.async - Async programming and communication https://github.com/clojure/core.async
    ;; Component - Component Lifecycle https://github.com/stuartsierra/component
    ;; RethinkDB - RethinkDB client for Clojure https://github.com/apa512/clj-rethinkdb
    ;; Schema - Data validation https://github.com/Prismatic/schema
    ;; Timbre - Pure Clojure/Script logging library https://github.com/ptaoussanis/timbre
    ;; Amazonica - A comprehensive Clojure client for the AWS API. https://github.com/mcohen01/amazonica
    ;; Raven - Interface to Sentry error reporting https://github.com/sethtrain/raven-clj
    ;; Cheshire - JSON encoding / decoding https://github.com/dakrone/cheshire
    ;; clj-jwt - A Clojure library for JSON Web Token(JWT) https://github.com/liquidz/clj-jwt
    ;; clj-time - Date and time lib https://github.com/clj-time/clj-time
    ;; environ - Environment settings from different sources https://github.com/weavejester/environ  ]
    ;; Clojure HTTP client https://github.com/dakrone/clj-http
    ;; Clojure wrapper for jsoup HTML parser https://github.com/mfornos/clojure-soup
    ;; String manipulation library https://github.com/funcool/cuerdas
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
        :db-name "open_company_auth_qa"
      }
      :plugins [
        ;; Linter https://github.com/jonase/eastwood
        [jonase/eastwood "0.3.5"]
        ;; Static code search for non-idiomatic code https://github.com/jonase/kibit        
        [lein-kibit "0.1.6" :exclusions [org.clojure/clojure]]
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
        :db-name "open_company_auth_dev"
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :aws-sqs-bot-queue "CHANGE-ME" ; SQS queue to read inbound notifications/requests
        :aws-sqs-storage-queue "CHANGE-ME" ; SQS queue to send requests to the Storage service
        :aws-s3-digest-banner-bucket "CHANGE-ME" ; S3 bucket for caching digest banners
        :aws-s3-digest-footer-bucket "CHANGE-ME" ; S3 bucket for storing/serving digest footers
        :filestack-api-key "CHANGE-ME"
        :log-level "debug"
      }
      :plugins [
        ;; Check for code smells https://github.com/dakrone/lein-bikeshed
        ;; NB: org.clojure/tools.cli is pulled in by lein-kibit
        [lein-bikeshed "0.5.2" :exclusions [org.clojure/tools.cli]] 
        ;; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-checkall "0.1.1"]
        ;; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-pprint "1.2.0"]
        ;; Check for outdated dependencies https://github.com/xsc/lein-ancient
        [lein-ancient "0.6.15"]
        ;; Catch spelling mistakes in docs and docstrings https://github.com/cldwalker/lein-spell
        [lein-spell "0.1.0"]
        ;; Dead code finder https://github.com/venantius/yagni
        [venantius/yagni "0.1.7" :exclusions [org.clojure/clojure]]
        ;; Autotest https://github.com/jakemcc/lein-test-refresh
        [com.jakemccrary/lein-test-refresh "0.24.1"]
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
                 '[clj-time.format :as f]
                 '[oc.lib.db.common :as db-common]
                 '[oc.bot.resources.slack-org :as slack-org]
                 '[oc.bot.resources.team :as team])
      ]
    }]

    ;; Production environment
    :prod {
      :env {
        :db-name "open_company_auth"
        :env "production"
      }      
    }

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

  ;; ----- Code check configuration -----

  :eastwood {
    ;; Disable some linters that are enabled by default
    ;; contant-test - just seems mostly ill-advised, logical constants are useful in something like a `->cond` 
    ;; wrong-arity - unfortunate, but it's failing on 3/arity of sqs/send-message
    ;; implicit-dependencies - uhh, just seems dumb
    :exclude-linters [:constant-test :wrong-arity :implicit-dependencies]
    ;; Enable some linters that are disabled by default
    :add-linters [:unused-namespaces :unused-private-vars] ; :unused-locals]
    ;; Exclude testing namespaces
    :tests-paths ["test"]
    :exclude-namespaces [:test-paths]
  }

  :main oc.bot.app
)