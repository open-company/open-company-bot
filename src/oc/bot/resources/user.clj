(ns oc.bot.resources.user
  "User stored in RethinkDB."
  (:require [schema.core :as schema]
            [oc.lib.db.common :as db-common]
            [oc.lib.db.pool :as pool]
            [oc.lib.schema :as lib-schema]))

(def table-name "users")

(schema/defn ^:always-validate user-for
  "
  Given the slack-org-id of the Slack org, and the Slack user-id of the user, retrieve the
  Carrot user, or return nil if it don't exist.
  "
  [db-pool slack-org-id :- lib-schema/NonBlankStr user-slack-id :- lib-schema/NonBlankStr]
  (pool/with-pool [conn db-pool]
    (first (db-common/read-resources conn table-name "user-slack-team-id" [[slack-org-id, user-slack-id]]))))