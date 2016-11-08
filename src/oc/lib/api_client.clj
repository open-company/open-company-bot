(ns oc.lib.api-client
  (:require [clj-http.client :as http]
            [taoensso.timbre :as timbre]
            [cheshire.core :as cheshire]
            [manifold.deferred :as d]
            [oc.bot.config :as c]))

(defn patch-company! [token slug data]
  (timbre/info "Updating Company:" slug data)
  (-> (http/patch (str c/oc-api-endpoint "/companies/" slug)
                  {:headers {"Authorization" (str "Bearer " token)
                             "Accept" "application/vnd.open-company.company.v1+json"
                             "Accept-Charset" "utf-8"
                             "Content-Type" "application/vnd.open-company.company.v1+json"}
                   :body (cheshire/generate-string data)})
      (d/chain (fn [response] (timbre/info "Company Updated:" (-> response :body :slug)) response) :body)
      (d/catch (fn [err] (timbre/error err)))))

(comment 

  @(patch-company! "buffer" {:description "Yada Yada"})

  (-> (patch-company! "buffer" {:description "Yada Yada"})
      (d/chain :body prn)
      (d/catch #(prn (ex-data %))))

  )