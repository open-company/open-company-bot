(ns oc.bot.resources.team
  "Team stored in RethinkDB."
  (:require [schema.core :as schema]
            [oc.lib.db.common :as db-common]
            [oc.lib.db.pool :as pool]
            [oc.lib.schema :as lib-schema]))

(def table-name "teams")

(schema/defn ^:always-validate teams-for :- (schema/maybe #{lib-schema/UniqueID})
  "Given the slack-org-id of the Slack org, retrieve the bot-token, or return nil if it don't exist."
  [db-pool slack-org-id :- lib-schema/NonBlankStr]
  (pool/with-pool [conn db-pool]
    (if-let [teams (db-common/read-resources conn table-name "slack-orgs" slack-org-id)]
      (set (map :team-id teams)))))