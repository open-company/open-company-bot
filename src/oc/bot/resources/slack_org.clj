(ns oc.bot.resources.slack-org
  "Slack org stored in RethinkDB."
  (:require [clojure.walk :refer (keywordize-keys)]
            [schema.core :as schema]
            [oc.lib.db.common :as db-common]
            [oc.lib.db.pool :as pool]
            [oc.lib.schema :as lib-schema]))

;; ----- RethinkDB metadata -----

(def table-name "slack_orgs")

;; ----- Utility functions -----

(schema/defn ^:always-validate bot-token-for :- (schema/maybe lib-schema/NonBlankStr)
  "Given the slack-org-id of the Slack org, retrieve the bot-token, or return nil if it don't exist."
  [db-pool slack-org-id :- lib-schema/NonBlankStr]
  (pool/with-pool [conn db-pool]
    (if-let [slack-org (db-common/read-resource conn table-name slack-org-id)]
      (:bot-token slack-org))))